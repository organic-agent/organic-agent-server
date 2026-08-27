#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

RUN_LIMIT = 21_000
repo_root = File.expand_path("..", __dir__)
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
