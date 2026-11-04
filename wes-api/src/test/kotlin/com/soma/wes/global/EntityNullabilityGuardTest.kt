package com.soma.wes.global

import com.soma.wes.support.IntegrationTest
import jakarta.persistence.AttributeOverride
import jakarta.persistence.Column
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.engine.spi.SessionFactoryImplementor
import org.hibernate.metamodel.mapping.AttributeMapping
import org.hibernate.metamodel.mapping.BasicValuedModelPart
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.lang.reflect.Field
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

/**
 * 엔티티의 NULL 허용(Kotlin 타입의 `?`)·`@Column(nullable)`과 Flyway가 만든 DB 컬럼의 `NOT NULL`이 어긋나지 않게 막는다(DM-10).
 * `ddl-auto=validate`는 NULL 허용을 비교하지 않아서, 어긋나면 컴파일도 앱 시작도 통과하고
 * Kotlin `String?` ↔ DB `NOT NULL`은 저장 순간 DB 오류, Kotlin `String` ↔ DB NULL 허용은 NULL 행을 읽을 때 NPE가 된다.
 * `@Column(nullable)`은 아무것도 막지 않지만 엔티티를 읽는 사람이 믿는 값이라, 어긋나면 틀린 사실을 알려 준다(DM-13).
 * 일부러 다르게 둔 것만 [AUDITED]·[INTENDED]에 까닭과 함께 둔다.
 */
@IntegrationTest
class EntityNullabilityGuardTest @Autowired constructor(
    private val entityManagerFactory: EntityManagerFactory,
    private val jdbcTemplate: JdbcTemplate,
) {

    companion object {
        /**
         * [BaseEntity]의 감사 시각. 저장 전에는 값이 없어 Kotlin은 `?`이고, 저장하는 순간 auditing(`@CreatedDate`·`@LastModifiedDate`)이
         * 채운다 — DB가 `NOT NULL`이어도 저장 오류가 나지 않고, 읽은 행에서는 늘 값이 있다. 이 방향(Kotlin `?` ↔ DB NOT NULL)만 허용한다.
         */
        private val AUDITED = setOf("createdAt", "updatedAt")

        /** 그 밖에 일부러 Kotlin과 DB의 NULL 허용을 다르게 둔 컬럼(`테이블.컬럼`) — 바로 위 줄에 까닭을 적는다. */
        private val INTENDED = emptySet<String>()

        private fun dbLabel(nullableInDb: Boolean): String = if (nullableInDb) "DB NULL 허용" else "DB NOT NULL"
    }

    @Test
    fun `엔티티 필드의 NULL 허용이 DB 컬럼과 같다`() {
        // given
        val dbNullable = dbColumns()

        // when
        val mismatches = mappedColumns()
            .filter { "${it.table}.${it.column}" !in INTENDED }
            .mapNotNull { column ->
                val nullableInDb = dbNullable[column.table to column.column] ?: return@mapNotNull null
                when {
                    column.nullableInKotlin == nullableInDb -> null
                    column.audited && !nullableInDb -> null
                    else -> column.describe(nullableInDb)
                }
            }

        // then
        assertThat(mismatches).isEmpty()
    }

    @Test
    fun `@Column의 nullable이 DB 컬럼과 같다`() {
        // given
        val dbNullable = dbColumns()

        // when
        val mismatches = mappedColumns()
            .filter { "${it.table}.${it.column}" !in INTENDED }
            .mapNotNull { column ->
                val nullableInDb = dbNullable[column.table to column.column] ?: return@mapNotNull null
                val declared = column.nullableInAnnotation ?: return@mapNotNull null
                if (declared == nullableInDb) {
                    null
                } else {
                    "${column.table}.${column.column} (${column.owner}): @Column(nullable = $declared) ↔ ${dbLabel(nullableInDb)}"
                }
            }

        // then
        assertThat(mismatches).isEmpty()
    }

    @Test
    fun `스캔이 엔티티 컬럼을 DB에서 찾는다`() {
        // when
        val dbNullable = dbColumns()
        val mapped = mappedColumns()

        // then
        assertThat(mapped).hasSizeGreaterThan(100)
        assertThat(mapped.filter { (it.table to it.column) !in dbNullable }.map { "${it.table}.${it.column}" }).isEmpty()
    }

    private fun dbColumns(): Map<Pair<String, String>, Boolean> =
        jdbcTemplate.query(
            "SELECT table_name, column_name, is_nullable FROM information_schema.columns WHERE table_schema = current_schema()",
        ) { rs, _ -> (rs.getString("table_name") to rs.getString("column_name")) to (rs.getString("is_nullable") == "YES") }
            .toMap()

    private fun mappedColumns(): List<MappedColumn> {
        val result = mutableListOf<MappedColumn>()
        entityManagerFactory.unwrap(SessionFactoryImplementor::class.java).mappingMetamodel.forEachEntityDescriptor { persister ->
            val owner = persister.mappedClass.kotlin
            persister.forEachAttributeMapping { collect(owner, it, outerNullable = false, result) }
        }
        return result.distinctBy { Triple(it.table, it.column, it.owner) }
    }

    /** 단순 컬럼과 임베디드 값 객체 안의 컬럼을 모은다. 연관(`@ManyToOne` 등)·컬렉션은 id 컬럼만 쓰는 이 코드베이스에 거의 없어 보지 않는다. */
    private fun collect(owner: KClass<*>, attribute: AttributeMapping, outerNullable: Boolean, into: MutableList<MappedColumn>) {
        val property = owner.memberProperties.firstOrNull { it.name == attribute.attributeName } ?: return
        val nullable = outerNullable || property.returnType.isMarkedNullable
        when (attribute) {
            is BasicValuedModelPart -> into += MappedColumn(
                table = attribute.containingTableExpression.substringAfterLast('.').trim('"'),
                column = attribute.selectionExpression.trim('"'),
                owner = "${owner.simpleName}.${attribute.attributeName}",
                nullableInKotlin = nullable,
                nullableInAnnotation = declaredNullable(owner, property.name, property.javaField),
                audited = owner.isSubclassOf(BaseEntity::class) && property.name in AUDITED,
            )
            is EmbeddableValuedModelPart -> {
                val embeddable = attribute.embeddableTypeDescriptor
                val type = embeddable.mappedJavaType.javaTypeClass.kotlin
                embeddable.forEachAttributeMapping { inner -> collect(type, inner, nullable, into) }
            }
            else -> Unit
        }
    }

    /**
     * 엔티티가 `@AttributeOverride`로 덮은 정의가 먼저다(감사 시각이 NOT NULL인 billing 테이블이 [BaseEntity]의 공용 정의를 덮는다).
     * 없으면 필드의 `@Column`. 둘 다 없으면(`@Id` 등) null.
     */
    private fun declaredNullable(owner: KClass<*>, attributeName: String, field: Field?): Boolean? =
        owner.java.getAnnotationsByType(AttributeOverride::class.java).firstOrNull { it.name == attributeName }?.column?.nullable
            ?: field?.getAnnotation(Column::class.java)?.nullable

    private data class MappedColumn(
        val table: String,
        val column: String,
        val owner: String,
        val nullableInKotlin: Boolean,
        /** 필드에 `@Column`이 없으면(`@Id` 등) null — 비교하지 않는다. */
        val nullableInAnnotation: Boolean?,
        val audited: Boolean,
    ) {
        fun describe(nullableInDb: Boolean): String {
            val kotlin = if (nullableInKotlin) "Kotlin ?(NULL 허용)" else "Kotlin non-null"
            return "$table.$column ($owner): $kotlin ↔ ${dbLabel(nullableInDb)}"
        }
    }
}
