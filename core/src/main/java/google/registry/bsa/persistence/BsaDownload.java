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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.bsa.DownloadStage.DOWNLOAD;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.bsa.BlockList;
import google.registry.bsa.DownloadStage;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.persistence.VKey;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

/** Records of ongoing and completed download jobs. */
@Entity
@Table(indexes = {@Index(columnList = "creationTime")})
public class BsaDownload {

  private static final Joiner CSV_JOINER = Joiner.on(',');
  private static final Splitter CSV_SPLITTER = Splitter.on(',');

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long jobId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTime = CreateAutoTimestamp.create(null);

  @Column(nullable = false)
  UpdateAutoTimestamp updateTime = UpdateAutoTimestamp.create(null);

  @Column(nullable = false)
  String blockListChecksums = "";

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  DownloadStage stage = DOWNLOAD;

  BsaDownload() {}

  public long getJobId() {
    return jobId;
  }

  /**
   * Returns the starting time of this job as a string, which can be used as folder name on GCS when
   * storing download data.
   */
  public String getJobName() {
    return creationTime.getTimestamp().toString();
  }

  public DownloadStage getStage() {
    return this.stage;
  }

  BsaDownload setStage(DownloadStage stage) {
    this.stage = stage;
    return this;
  }

  DateTime getCreationTime() {
    return creationTime.getTimestamp();
  }

  BsaDownload setBlockListChecksums(ImmutableMap<BlockList, String> checksums) {
    blockListChecksums =
        CSV_JOINER.withKeyValueSeparator("=").join(ImmutableSortedMap.copyOf(checksums));
    return this;
  }

  ImmutableMap<BlockList, String> getChecksums() {
    if (blockListChecksums.isEmpty()) {
      return ImmutableMap.of();
    }
    return CSV_SPLITTER.withKeyValueSeparator('=').split(blockListChecksums).entrySet().stream()
        .collect(
            toImmutableMap(entry -> BlockList.valueOf(entry.getKey()), entry -> entry.getValue()));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BsaDownload)) {
      return false;
    }
    BsaDownload that = (BsaDownload) o;
    return Objects.equal(creationTime, that.creationTime)
        && Objects.equal(updateTime, that.updateTime)
        && Objects.equal(blockListChecksums, that.blockListChecksums)
        && stage == that.stage;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(creationTime, updateTime, blockListChecksums, stage);
  }

  static VKey<BsaDownload> vKey(long jobId) {
    return VKey.create(BsaDownload.class, Long.valueOf(jobId));
  }
}
