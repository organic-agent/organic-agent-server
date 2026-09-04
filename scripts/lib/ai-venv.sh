#!/usr/bin/env bash
# AI repo(organic-agent-ai) 배치 모듈의 venv 를 찾거나 만든다. local-worker.sh·local-ai.sh 가 source 한다.
#
#   . scripts/lib/ai-venv.sh
#   PY="$(ai_python "$AI_ROOT" score)"        # score/.venv — categorize 도 같은 venv 에 editable 로 깔린다(체인용)
#   PY="$(ai_python "$AI_ROOT" embedder)"     # embedder/.venv
#
# AI repo #35 이후 배치 모듈은 최상위 디렉토리 하나 = Lambda 함수 하나다: embedder / score / categorize. 모듈마다
# 자기 requirements.txt 가 있고, venv 는 그 디렉토리 아래 .venv 에 둔다. 없으면 만든다 — torch 모듈(embedder·score)은
# 첫 실행이 수 GB 다. 리눅스에서는 CUDA 빌드가 딸려오지 않게 CPU 인덱스로, 맥은 pypi 기본 빌드가 MPS 를 잡는다.
# score venv 에는 categorize 도 같이 깐다 — score 가 끝에서 `python -m categorize` 를 서브프로세스로 띄우기 때문이다.
# 환경변수 AI_PY 로 다른 인터프리터를 강제할 수 있다(모듈 무관).

ai_python() {
  local ai_root="$1" mod="$2"
  local dir="$ai_root/$mod"
  if [ ! -d "$dir/$mod" ]; then
    echo "AI repo 의 $mod 패키지를 찾지 못했다: $dir/$mod (AI_ROOT 환경변수로 지정, AI repo #35 이후 구조)" >&2
    return 1
  fi
  if [ -n "${AI_PY:-}" ]; then
    echo "$AI_PY"
    return 0
  fi
  local py="$dir/.venv/bin/python"
  if [ ! -x "$py" ]; then
    # Python 3.12 고정 — torch 2.4.1 핀은 3.13+ 에 휠이 없다. uv 가 있으면 3.12 를 알아서 받는다.
    echo "[setup] venv 생성: $dir/.venv (Python 3.12, 첫 실행은 수 GB 일 수 있다)" >&2
    if command -v uv >/dev/null 2>&1; then
      uv venv -q --python 3.12 "$dir/.venv" >&2
    elif command -v python3.12 >/dev/null 2>&1; then
      python3.12 -m venv "$dir/.venv"
    else
      echo "Python 3.12 가 없다. uv(brew install uv) 또는 python3.12 를 설치할 것." >&2
      return 1
    fi
    if [ "$(uname -s)" = "Linux" ] && grep -q '^torch' "$dir/requirements.txt"; then
      _ai_pip "$py" install torch torchvision --index-url https://download.pytorch.org/whl/cpu >&2
    fi
    _ai_pip "$py" install -r "$dir/requirements.txt" >&2
  fi
  if ! "$py" -c "import $mod.job" 2>/dev/null; then
    echo "[setup] pip install -e $dir (최초 1회)" >&2
    _ai_pip "$py" install -e "$dir" --no-deps >&2
  fi
  if [ "$mod" = "score" ] && ! "$py" -c "import categorize.job" 2>/dev/null; then
    echo "[setup] pip install -e $ai_root/categorize (score 체인용, 최초 1회)" >&2
    _ai_pip "$py" install -r "$ai_root/categorize/requirements.txt" >&2
    _ai_pip "$py" install -e "$ai_root/categorize" --no-deps >&2
  fi
  echo "$py"
}

# uv 로 만든 venv 에는 pip 이 없다 — uv pip 을 쓰고, 없으면 python -m pip.
_ai_pip() {
  local py="$1"; shift
  if command -v uv >/dev/null 2>&1; then
    uv pip -q --python "$py" "$@"
  else
    "$py" -m pip install -q "${@:2}"
  fi
}
