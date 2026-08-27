def valid_optional_count($key):
  .imageScanFindings.findingSeverityCounts as $counts
  | if ($counts | has($key)) then
      ($counts[$key] | type == "number" and . >= 0 and floor == .)
    else
      true
    end;

def count_or_zero($key):
  .imageScanFindings.findingSeverityCounts as $counts
  | if ($counts | has($key)) then $counts[$key] else 0 end;

if .imageId.imageDigest != $digest then
  error("worker ECR scan digest mismatch")
elif .imageScanStatus.status != "COMPLETE" then
  error("worker ECR scan is not complete")
elif (.imageScanFindings | type) != "object" then
  error("worker ECR imageScanFindings is missing or malformed")
elif (.imageScanFindings.findingSeverityCounts | type) != "object" then
  error("worker ECR findingSeverityCounts is missing or malformed")
elif (valid_optional_count("CRITICAL") | not) or (valid_optional_count("HIGH") | not) then
  error("worker ECR blocking severity count is not a non-negative integer")
else
  {
    critical: count_or_zero("CRITICAL"),
    high: count_or_zero("HIGH")
  }
end
