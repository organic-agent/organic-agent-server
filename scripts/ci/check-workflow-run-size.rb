#!/usr/bin/env ruby
# frozen_string_literal: true
# 워크플로 run 크기 검사 — .github/workflows 의 run 단계가 GitHub 상한(RUN_LIMIT)을 넘지 않는지 본다(ci.yml).
# 대상: 없음(저장소 파일만 읽음)
# 바꾸는 것: 없음(읽기 전용)
#
#   ruby scripts/ci/check-workflow-run-size.rb [워크플로 파일 …]

require "yaml"

RUN_LIMIT = 21_000
repo_root = File.expand_path("../..", __dir__)
workflow_paths = if ARGV.empty?
                   Dir[File.join(repo_root, ".github/workflows/*.{yml,yaml}")].sort
                 else
                   ARGV.map { |path| File.expand_path(path) }
                 end

violations = []
max_run = nil

workflow_paths.each do |path|
  workflow = YAML.load_file(path)
  jobs = workflow.fetch("jobs", {})
  jobs.each do |job_id, job|
    next unless job.is_a?(Hash)

    Array(job["steps"]).each_with_index do |step, index|
      next unless step.is_a?(Hash) && step["run"].is_a?(String)

      length = step["run"].length
      label = "#{File.basename(path)} #{job_id} step #{index + 1} (#{step["name"] || "unnamed"})"
      max_run = [max_run, [length, label]].compact.max_by(&:first)
      violations << [length, label] if length > RUN_LIMIT
    end
  end
end

if violations.any?
  violations.each do |length, label|
    warn "#{label}: run scalar #{length} characters exceeds GitHub Actions limit #{RUN_LIMIT}"
  end
  exit 1
end

abort "No run steps found in workflow files" unless max_run
puts "Workflow run-size checks passed (max #{max_run[0]} characters: #{max_run[1]})"
