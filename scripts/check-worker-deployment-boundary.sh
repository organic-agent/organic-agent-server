#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd_workflow="$repo_root/.github/workflows/cd.yml"

worker_block="$(mktemp)"
cleanup() {
  rm -f -- "$worker_block"
}
trap cleanup EXIT HUP INT TERM

[ "$(grep -c '^  deploy-worker:$' "$cd_workflow")" -eq 1 ]
[ "$(grep -c '^  deploy-admin:$' "$cd_workflow")" -eq 1 ]
awk '
  /^  deploy-worker:$/ { in_worker = 1 }
  /^  deploy-admin:$/ && in_worker { exit }
  in_worker { print }
' "$cd_workflow" > "$worker_block"
[ -s "$worker_block" ]

# Worker invariants must occur inside deploy-worker, not merely in another job or a comment.
grep -Fq 'needs: deploy-public' "$worker_block"
grep -Fq 'needs: [build-and-push, deploy-public, deploy-worker]' "$cd_workflow"
grep -Fq 'secrets.AWS_WORKER_DEPLOY_ROLE_ARN' "$worker_block"
grep -Fq 'ECR_REPOSITORY_URI: 233927217926.dkr.ecr.ap-northeast-2.amazonaws.com/wes-embedder' "$worker_block"
grep -Fq 'FUNCTION_NAME: wes-embedder' "$worker_block"
grep -Fq 'DEPLOY_TAG: deploy-${{ github.run_id }}-${{ github.run_attempt }}' "$worker_block"
grep -Fq 'uses: actions/setup-python@v5' "$worker_block"
grep -Fq 'python-version: "3.12"' "$worker_block"
grep -Fq 'scripts/normalize-worker-scan-findings.jq' "$worker_block"
grep -Fq 'pip-audit==2.10.1' "$worker_block"
grep -Fq 'embedder/requirements.txt' "$worker_block"
grep -Fq 'embedder/requirements-audit.txt' "$worker_block"
grep -Fq -- '--no-deps' "$worker_block"
grep -Fq -- '--disable-pip' "$worker_block"
grep -Fq -- '--vulnerability-service osv' "$worker_block"
grep -Fq 'platforms: linux/amd64' "$worker_block"
grep -Fq 'provenance: false' "$worker_block"
grep -Fq 'sbom: false' "$worker_block"
grep -Fq 'HF_TOKEN: ${{ secrets.HF_TOKEN }}' "$worker_block"
grep -Fq 'hf_token=${{ secrets.HF_TOKEN }}' "$worker_block"
grep -Fq '${{ env.ECR_REPOSITORY_URI }}:${{ github.sha }}' "$worker_block"
grep -Fq '${{ env.ECR_REPOSITORY_URI }}:${{ env.DEPLOY_TAG }}' "$worker_block"
grep -Fq 'for image_tag in "${{ github.sha }}" "$DEPLOY_TAG"; do' "$worker_block"
grep -Fq 'CANDIDATE_DIGEST: ${{ steps.worker_build.outputs.digest }}' "$worker_block"
grep -Fq 'aws ecr describe-images' "$worker_block"
grep -Fq 'imageManifestMediaType' "$worker_block"
grep -Fq 'application/vnd.docker.distribution.manifest.v2+json' "$worker_block"
grep -Fq 'application/vnd.oci.image.manifest.v1+json' "$worker_block"
grep -Fq 'aws ecr describe-image-scan-findings' "$worker_block"
grep -Fq 'SCAN_FINDINGS=$(aws ecr describe-image-scan-findings' "$worker_block"
grep -Fq 'NORMALIZED_SCAN_COUNTS=$(printf' "$worker_block"
grep -Fq -- '-f scripts/normalize-worker-scan-findings.jq' "$worker_block"
grep -Fq 'CRITICAL_COUNT=$(printf' "$worker_block"
grep -Fq 'HIGH_COUNT=$(printf' "$worker_block"
grep -Fq 'case "$CRITICAL_COUNT" in' "$worker_block"
grep -Fq 'case "$HIGH_COUNT" in' "$worker_block"
grep -Fq 'CANDIDATE_IMAGE_URI="${ECR_REPOSITORY_URI}@${CANDIDATE_DIGEST}"' "$worker_block"
grep -Fq 'PREVIOUS_REVISION_ID=$(printf' "$worker_block"
grep -Fq 'RECONCILIATION_MIN_SECONDS=60' "$worker_block"
grep -Fq 'reconcile_worker_update()' "$worker_block"
grep -Fq '[ "$reconciliation_elapsed" -ge "$RECONCILIATION_MIN_SECONDS" ]' "$worker_block"
grep -Fq -- '--revision-id "$PREVIOUS_REVISION_ID"' "$worker_block"
grep -Fq -- '--revision-id "$RECONCILED_REVISION_ID"' "$worker_block"
grep -Fq 'wait_for_worker_terminal()' "$worker_block"
grep -Fq 'ROLLBACK_ARMED=true' "$worker_block"
grep -Fq 'rollback_worker()' "$worker_block"
grep -Fq 'aws lambda wait function-updated-v2' "$worker_block"
grep -Fq '.LastUpdateStatus == "Successful"' "$worker_block"
grep -Fq '.Architectures == ["x86_64"]' "$worker_block"
grep -Fq '[ "$RESOLVED_IMAGE_URI" = "$CANDIDATE_IMAGE_URI" ]' "$worker_block"

worker_dockerfile="$repo_root/embedder/Dockerfile"
grep -Fq 'FROM public.ecr.aws/lambda/python:3.12@sha256:' "$worker_dockerfile"
grep -Fq 'torch==2.13.0+cpu torchvision==0.28.0+cpu' "$worker_dockerfile"
grep -Fq 'ARG EMBED_MODEL_REVISION=5931719e67bbdb9737e363e781fb0c67687896bc' "$worker_dockerfile"
grep -Fq 'revision=r' "$worker_dockerfile"
grep -Fq 'required=true' "$worker_dockerfile"
grep -Fq 'from embedder.handler import handler; assert callable(handler)' "$worker_dockerfile"

worker_requirements="$repo_root/embedder/requirements.txt"
if grep -Ev '^[[:space:]]*(#|$)' "$worker_requirements" | grep -Evq '=='; then
  echo "Worker direct dependency must be pinned to an exact version." >&2
  exit 1
fi

worker_audit_requirements="$repo_root/embedder/requirements-audit.txt"
grep -Fq -- '-r requirements.txt' "$worker_audit_requirements"
grep -Fq 'torch==2.13.0' "$worker_audit_requirements"
grep -Fq 'torchvision==0.28.0' "$worker_audit_requirements"

scan_validator="$repo_root/scripts/normalize-worker-scan-findings.jq"
candidate_digest="sha256:worker-scan-contract"
valid_empty_counts='{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{}}}'
normalized_empty_counts="$(printf '%s' "$valid_empty_counts" |
  jq -ce --arg digest "$candidate_digest" -f "$scan_validator")"
[ "$normalized_empty_counts" = '{"critical":0,"high":0}' ]

valid_nonzero_counts='{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{"CRITICAL":0,"HIGH":2}}}'
normalized_nonzero_counts="$(printf '%s' "$valid_nonzero_counts" |
  jq -ce --arg digest "$candidate_digest" -f "$scan_validator")"
[ "$normalized_nonzero_counts" = '{"critical":0,"high":2}' ]

invalid_scan_fixtures=(
  '{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"}}'
  '{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{"CRITICAL":false,"HIGH":0}}}'
  '{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{"CRITICAL":"0","HIGH":0}}}'
  '{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{"CRITICAL":-1,"HIGH":0}}}'
  '{"imageId":{"imageDigest":"sha256:worker-scan-contract"},"imageScanStatus":{"status":"COMPLETE"},"imageScanFindings":{"findingSeverityCounts":{"CRITICAL":0.5,"HIGH":0}}}'
)
for invalid_scan_fixture in "${invalid_scan_fixtures[@]}"; do
  if printf '%s' "$invalid_scan_fixture" |
    jq -ce --arg digest "$candidate_digest" -f "$scan_validator" >/dev/null 2>&1; then
    echo "Malformed worker scan fixture unexpectedly passed validation." >&2
    exit 1
  fi
done

if awk '
  /--image-uri/ {
    image_argument = $0
    if ((getline following_line) > 0) {
      image_argument = image_argument "\n" following_line
    }
    if (image_argument ~ /github\.sha|DEPLOY_TAG/) {
      mutable_tag = 1
    }
  }
  END { exit mutable_tag ? 0 : 1 }
' "$worker_block"; then
  echo "Lambda function code must be updated by immutable digest, not a mutable tag." >&2
  exit 1
fi

python_setup_line="$(grep -n -m1 'uses: actions/setup-python@v5' "$worker_block" | cut -d: -f1)"
pip_audit_line="$(grep -n -m1 'pip-audit==2.10.1' "$worker_block" | cut -d: -f1)"
aws_credentials_line="$(grep -n -m1 'uses: aws-actions/configure-aws-credentials@v4' "$worker_block" | cut -d: -f1)"
if [ "$python_setup_line" -ge "$pip_audit_line" ] || \
   [ "$pip_audit_line" -ge "$aws_credentials_line" ]; then
  echo "Worker supply-chain checks must finish before AWS credentials are issued." >&2
  exit 1
fi

public_line="$(grep -n '^  deploy-public:' "$cd_workflow" | cut -d: -f1)"
worker_line="$(grep -n '^  deploy-worker:' "$cd_workflow" | cut -d: -f1)"
admin_line="$(grep -n '^  deploy-admin:' "$cd_workflow" | cut -d: -f1)"
if [ "$public_line" -ge "$worker_line" ] || [ "$worker_line" -ge "$admin_line" ]; then
  echo "Deployment order must remain public migration -> worker -> admin API." >&2
  exit 1
fi

echo "Worker deployment boundary checks passed"
