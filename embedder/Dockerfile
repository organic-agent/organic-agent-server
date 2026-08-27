# Lambda 컨테이너 이미지. zip 패키지는 압축 해제 250MB 한도라 torch 하나로 넘는다.
FROM public.ecr.aws/lambda/python:3.12@sha256:8f8b720c9ec0cf3da897db1dc2c82398b9fcc34e72eb08c8c87dac4ea3322a6a

# CPU 전용 휠. 기본 PyPI에서 받으면 CUDA 런타임이 함께 들어와 이미지가 GB 단위로 불어나는데,
# Lambda에는 GPU가 없어서 전부 죽은 무게다. transformers가 이 인덱스에 없으므로 분리한다.
#
# torchvision도 필요하다. 최신 transformers의 AutoImageProcessor는 fast 이미지 프로세서를
# 기본으로 쓰고 그게 torchvision 백엔드를 요구해서, 없으면 모델을 받는 단계에서 ImportError로
# 죽는다("requires the Torchvision library"). torch와 같은 인덱스로 받아 버전을 맞춘다.
RUN pip install --no-cache-dir \
      torch==2.13.0+cpu torchvision==0.28.0+cpu \
      --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# RDS의 CA 번들. IAM 인증은 sslmode=verify-full로 붙으므로 이 파일이 없으면 접속 자체가
# 실패한다. 런타임에 받아올 수는 없다 -- 이 함수가 붙는 서브넷에는 인터넷 경로가 없다.
RUN mkdir -p /opt/rds-ca \
 && curl -fsSL -o /opt/rds-ca/global-bundle.pem \
      https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem

# 가중치를 빌드 타임에 굽는다. 실행 시점의 Lambda는 NAT 없는 프라이빗 서브넷에 있어
# huggingface.co에 나갈 수 없다. 여기서 받아두지 않으면 첫 호출이 네트워크 타임아웃으로 죽는다.
ENV HF_HOME=/opt/hf
ARG EMBED_MODEL_ID=facebook/dinov2-base
ARG EMBED_MODEL_REVISION=f9e44c814b77203eaa57a6bdbbd535f21ede1415
RUN python -c "from transformers import AutoImageProcessor, AutoModel; \
m = '${EMBED_MODEL_ID}'; r = '${EMBED_MODEL_REVISION}'; \
AutoImageProcessor.from_pretrained(m, revision=r); AutoModel.from_pretrained(m, revision=r)"

# 런타임 파일시스템은 /tmp 말고는 읽기 전용이다. 캐시를 다시 쓰려 하지 않도록 오프라인으로 못박는다.
ENV HF_HUB_OFFLINE=1 \
    TRANSFORMERS_OFFLINE=1 \
    EMBED_MODEL_ID=${EMBED_MODEL_ID} \
    EMBED_MODEL_REVISION=${EMBED_MODEL_REVISION}

COPY embedder/ ${LAMBDA_TASK_ROOT}/embedder/

# 배포 뒤 첫 호출에서야 import 오류를 발견하지 않도록 이미지 빌드 안에서 실제 handler를
# 로드한다. Settings가 import 시 필수 환경변수를 검증하므로 비밀이 아닌 더미 값만 넣는다.
RUN DB_HOST=localhost \
    DB_NAME=wes \
    DB_USER=wes \
    DB_PASSWORD=build-only \
    S3_BUCKET=build-only \
    python -c "from embedder.handler import handler; assert callable(handler)"

CMD ["embedder.handler.handler"]
