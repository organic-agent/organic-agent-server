#!/usr/bin/env bash
# AI repo(organic-agent-ai) photoselect 의 venv 를 찾거나 만든다. local-worker.sh·local-ai.sh 가 source 한다.
#
#   . scripts/lib/ai-venv.sh
#   AI_PY="$(photoselect_python "$AI_ROOT")"
#
# venv 위치는 $AI_ROOT/photoselect/.venv (AI repo #31 이후 패키지가 photoselect/photoselect/ 평탄 구조라
# 그 옆에 둔다). 없으면 만든다 — torch·open_clip·ARNIQA 의존이라 첫 실행은 수 GB 를 받는다. 리눅스에서는
# CUDA 빌드가 딸려오지 않게 CPU 인덱스로, 맥은 pypi 기본 빌드가 MPS 를 잡는다. editable 설치까지 해서
# 어느 디렉토리에서든 `-m photoselect` 가 잡히게 한다. 환경변수 AI_PY 로 다른 인터프리터를 강제할 수 있다.

photoselect_python() {
  local ai_root="$1"
  local ps_dir="$ai_root/photoselect"
  if [ ! -d "$ps_dir/photoselect" ]; then
    echo "AI repo의 photoselect 패키지를 찾지 못했다: $ps_dir/photoselect (AI_ROOT 환경변수로 지정, AI repo #31 이후 구조)" >&2
    return 1
  fi
  if [ -n "${AI_PY:-}" ]; then
    echo "$AI_PY"
    return 0
  fi
  local py="$ps_dir/.venv/bin/python"
  if [ ! -x "$py" ]; then
    echo "[setup] venv 생성: $ps_dir/.venv (torch 포함, 첫 실행은 수 GB)" >&2
    python3 -m venv "$ps_dir/.venv"
    if [ "$(uname -s)" = "Linux" ]; then
      "$py" -m pip install -q torch torchvision --index-url https://download.pytorch.org/whl/cpu >&2
    fi
    "$py" -m pip install -q -r "$ps_dir/requirements.txt" >&2
  fi
  if ! "$py" -c "import photoselect.worker" 2>/dev/null; then
    echo "[setup] pip install -e $ps_dir (최초 1회)" >&2
    "$py" -m pip install -q -e "$ps_dir" --no-deps >&2
  fi
  echo "$py"
}
