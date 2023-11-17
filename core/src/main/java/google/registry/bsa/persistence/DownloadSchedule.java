// Copyright 2023 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.bsa.persistence;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import google.registry.bsa.BlockList;
import google.registry.bsa.DownloadStage;
import java.util.Optional;

/** Information needed when handling a download from BSA. */
@AutoValue
public abstract class DownloadSchedule {

  abstract long jobId();

  public abstract String jobName();

  public abstract DownloadStage stage();

  /** The most recent job that ended in the {@code DONE} stage. */
  public abstract Optional<CompletedJob> latestCompleted();

  /**
   * Returns true if download should be processed even if the checksums show that it has not changed
   * from the previous one.
   */
  abstract boolean alwaysDownload();

  static DownloadSchedule of(BsaDownload currentJob) {
    return new AutoValue_DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.empty(),
        /* alwaysDownload= */ true);
  }

  static DownloadSchedule of(
      BsaDownload currentJob, CompletedJob latestCompleted, boolean alwaysDownload) {
    return new AutoValue_DownloadSchedule(
        currentJob.getJobId(),
        currentJob.getJobName(),
        currentJob.getStage(),
        Optional.of(latestCompleted),
        /* alwaysDownload= */ alwaysDownload);
  }

  /** Information about a completed BSA download job. */
  @AutoValue
  public abstract static class CompletedJob {
    public abstract String jobName();

    public abstract ImmutableMap<BlockList, String> checksums();

    static CompletedJob of(BsaDownload completedJob) {
      return new AutoValue_DownloadSchedule_CompletedJob(
          completedJob.getJobName(), completedJob.getChecksums());
    }
  }
}
