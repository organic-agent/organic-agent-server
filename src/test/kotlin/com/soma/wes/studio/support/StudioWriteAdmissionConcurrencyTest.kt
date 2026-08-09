package com.soma.wes.studio.support

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.studio.domain.Studio
import com.soma.wes.studio.repository.StudioDeletionAuditRepository
import com.soma.wes.studio.repository.StudioDeletionClaimRepository
import com.soma.wes.studio.repository.StudioRepository
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    StudioWriteAdmission::class,
    StudioDeletionProcessor::class,
)
class StudioWriteAdmissionConcurrencyTest @Autowired constructor(
    private val admission: StudioWriteAdmission,
    private val processor: StudioDeletionProcessor,
    private val studioRepository: StudioRepository,
    private val galleryRepository: GalleryRepository,
    private val auditRepository: StudioDeletionAuditRepository,
    private val claimRepository: StudioDeletionClaimRepository,
    private val transactionManager: PlatformTransactionManager,
    private val jdbcTemplate: JdbcTemplate,
) {

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `먼저 입장한 gallery writer가 커밋되면 기다리던 삭제 준비가 새 행을 스냅샷에 포함한다`() {
        val transaction = TransactionTemplate(transactionManager)
        val userId = System.nanoTime()
        val studioId = transaction.execute {
            checkNotNull(
                studioRepository.saveAndFlush(
                    Studio(userId = userId, name = "writer-first", galleryUrl = "writer-first-studio"),
                ).id,
            )
        }
        val writerAdmitted = CountDownLatch(1)
        val allowWriterCommit = CountDownLatch(1)
        val deletionStarted = CountDownLatch(1)
        val deletionBackendPid = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val writer = executor.submit(Callable {
                transaction.executeWithoutResult {
                    admission.requireWritableByUserId(userId)
                    galleryRepository.saveAndFlush(Gallery(studioId = studioId, title = "먼저 들어온 갤러리"))
                    writerAdmitted.countDown()
                    assertTrue(allowWriterCommit.await(5, TimeUnit.SECONDS))
                }
            })
            assertTrue(writerAdmitted.await(5, TimeUnit.SECONDS))

            val deletion = executor.submit(Callable {
                transaction.execute {
                    deletionBackendPid.set(
                        checkNotNull(jdbcTemplate.queryForObject("select pg_backend_pid()", Int::class.java)),
                    )
                    deletionStarted.countDown()
                    processor.prepare(
                        studioId,
                        99L,
                        UUID.randomUUID(),
                        "writer-first-studio",
                        "문의 WES-CS-50 최종 확인",
                    )
                }
            })
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))
            awaitBlockedTransaction(deletionBackendPid.get())

            allowWriterCommit.countDown()
            writer.get(5, TimeUnit.SECONDS)

            val preparation = checkNotNull(deletion.get(5, TimeUnit.SECONDS))
            val plan = assertIs<StudioDeletionPreparation.Pending>(preparation).plan
            assertEquals(1, plan.galleryIds.size)
            processor.release(plan)
        } finally {
            allowWriterCommit.countDown()
            executor.shutdownNow()
            transaction.executeWithoutResult {
                claimRepository.deleteAllInBatch()
                auditRepository.deleteAllInBatch()
                studioRepository.deleteAllInBatch()
            }
        }
    }

    private fun awaitBlockedTransaction(backendPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            val blockerCount = checkNotNull(
                jdbcTemplate.queryForObject(
                    "select cardinality(pg_blocking_pids(?))",
                    Int::class.java,
                    backendPid,
                ),
            )
            if (blockerCount > 0) {
                return
            }
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)

        fail("deletion prepare transaction did not wait for the active gallery writer")
    }
}
