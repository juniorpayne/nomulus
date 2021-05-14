// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import google.registry.backup.AppEngineEnvironment;
import google.registry.model.ofy.ObjectifyService;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.QueryComposer;
import google.registry.persistence.transaction.TransactionManagerFactory;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import javax.persistence.criteria.CriteriaQuery;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;

/**
 * Contains IO {@link PTransform transforms} for a BEAM pipeline that interacts with a single
 * database through a {@link JpaTransactionManager}.
 *
 * <p>The {@code JpaTransactionManager} is instantiated once on each pipeline worker VM (through
 * {@link RegistryPipelineWorkerInitializer}), made available through the static method {@link
 * TransactionManagerFactory#jpaTm()}, and is shared by all threads on the VM. Configuration is
 * through {@link RegistryPipelineOptions}.
 */
public final class RegistryJpaIO {

  private RegistryJpaIO() {}

  public static <R> Read<R, R> read(QueryComposerFactory<R> queryFactory) {
    return Read.<R, R>builder().queryFactory(queryFactory).build();
  }

  public static <R, T> Read<R, T> read(
      QueryComposerFactory<R> queryFactory, SerializableFunction<R, T> resultMapper) {
    return Read.<R, T>builder().queryFactory(queryFactory).resultMapper(resultMapper).build();
  }

  public static <T> Write<T> write() {
    return Write.<T>builder().build();
  }

  // TODO(mmuller): Consider detached JpaQueryComposer that works with any JpaTransactionManager
  // instance, i.e., change composer.buildQuery() to composer.buildQuery(JpaTransactionManager).
  // This way QueryComposer becomes reusable and serializable (at least with Hibernate), and this
  // interface would no longer be necessary.
  public interface QueryComposerFactory<T>
      extends SerializableFunction<JpaTransactionManager, QueryComposer<T>> {}

  /**
   * A {@link PTransform transform} that transactionally executes a JPA {@link CriteriaQuery} and
   * adds the results to the BEAM pipeline. Users have the option to transform the results before
   * sending them to the next stages.
   */
  @AutoValue
  public abstract static class Read<R, T> extends PTransform<PBegin, PCollection<T>> {

    public static final String DEFAULT_NAME = "RegistryJpaIO.Read";

    abstract String name();

    abstract RegistryJpaIO.QueryComposerFactory<R> queryFactory();

    abstract SerializableFunction<R, T> resultMapper();

    abstract Coder<T> coder();

    abstract Builder<R, T> toBuilder();

    @Override
    @SuppressWarnings("deprecation") // Reshuffle still recommended by GCP.
    public PCollection<T> expand(PBegin input) {
      return input
          .apply("Starting " + name(), Create.of((Void) null))
          .apply(
              "Run query for " + name(),
              ParDo.of(new QueryRunner<>(queryFactory(), resultMapper())))
          .setCoder(coder())
          .apply("Reshuffle", Reshuffle.viaRandomKey());
    }

    public Read<R, T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Read<R, T> withResultMapper(SerializableFunction<R, T> mapper) {
      return toBuilder().resultMapper(mapper).build();
    }

    public Read<R, T> withCoder(Coder<T> coder) {
      return toBuilder().coder(coder).build();
    }

    static <R, T> Builder<R, T> builder() {
      return new AutoValue_RegistryJpaIO_Read.Builder()
          .name(DEFAULT_NAME)
          .resultMapper(x -> x)
          .coder(SerializableCoder.of(Serializable.class));
    }

    @AutoValue.Builder
    public abstract static class Builder<R, T> {

      abstract Builder<R, T> name(String name);

      abstract Builder<R, T> queryFactory(RegistryJpaIO.QueryComposerFactory<R> queryFactory);

      abstract Builder<R, T> resultMapper(SerializableFunction<R, T> mapper);

      abstract Builder<R, T> coder(Coder coder);

      abstract Read<R, T> build();
    }

    static class QueryRunner<R, T> extends DoFn<Void, T> {
      private final QueryComposerFactory<R> querySupplier;
      private final SerializableFunction<R, T> resultMapper;

      QueryRunner(QueryComposerFactory<R> querySupplier, SerializableFunction<R, T> resultMapper) {
        this.querySupplier = querySupplier;
        this.resultMapper = resultMapper;
      }

      @ProcessElement
      public void processElement(OutputReceiver<T> outputReceiver) {
        // AppEngineEnvironment is need for handling VKeys, which involve Ofy keys. Unlike
        // SqlBatchWriter, it is unnecessary to initialize ObjectifyService in this class.
        try (AppEngineEnvironment env = new AppEngineEnvironment()) {
          // TODO(b/187210388): JpaTransactionManager should support non-transactional query.
          jpaTm()
              .transactNoRetry(
                  () ->
                      querySupplier.apply(jpaTm()).withAutoDetachOnLoad(true).stream()
                          .map(resultMapper::apply)
                          .forEach(outputReceiver::output));
        }
      }
    }
  }

  /**
   * A {@link PTransform transform} that writes a PCollection of entities to the SQL database using
   * the {@link JpaTransactionManager}.
   *
   * <p>Unlike typical BEAM {@link Write} transforms, the output type of this transform is {@code
   * PCollection<Void>} instead of {@link org.apache.beam.sdk.values.PDone}. This deviation allows
   * the sequencing of multiple {@code PCollections}: we have use cases where one collection of data
   * must be completely written before another can start (due to foreign key constraints in the
   * latter).
   *
   * @param <T> type of the entities to be written
   */
  @AutoValue
  public abstract static class Write<T> extends PTransform<PCollection<T>, PCollection<Void>> {

    public static final String DEFAULT_NAME = "RegistryJpaIO.Write";

    public static final int DEFAULT_BATCH_SIZE = 1;

    /** The default number of write shard. Please refer to {@link #shards} for more information. */
    public static final int DEFAULT_SHARDS = 1;

    public abstract String name();

    /** Number of elements to be written in one call. */
    public abstract int batchSize();

    /**
     * The number of shards the output should be split into.
     *
     * <p>This value is a hint to the pipeline runner on the level of parallelism, and should be
     * significantly greater than the number of threads working on this transformation (see next
     * paragraph for more information). On the other hand, it should not be too large to the point
     * that the number of elements per shard is lower than {@link #batchSize()}. As a rule of thumb,
     * the following constraint should hold: {@code shards * batchSize * nThreads <=
     * inputElementCount}. Although it is not always possible to determine the number of threads
     * working on this transform, when the pipeline run is IO-bound, it most likely is close to the
     * total number of threads in the pipeline, which is explained below.
     *
     * <p>With Cloud Dataflow runner, the total number of worker threads in a batch pipeline (which
     * includes all existing Registry pipelines) is the number of vCPUs used by the pipeline, and
     * can be set by the {@code --maxNumWorkers} and {@code --workerMachineType} parameters. The
     * number of worker threads in a streaming pipeline can be set by the {@code --maxNumWorkers}
     * and {@code --numberOfWorkerHarnessThreads} parameters.
     *
     * <p>Note that connections on the database server are a limited resource, therefore the number
     * of threads that interact with the database should be set to an appropriate limit. Again, we
     * cannot control this number, but can influence it by controlling the total number of threads.
     */
    public abstract int shards();

    public abstract SerializableFunction<T, Object> jpaConverter();

    public Write<T> withName(String name) {
      return toBuilder().name(name).build();
    }

    public Write<T> withBatchSize(int batchSize) {
      return toBuilder().batchSize(batchSize).build();
    }

    public Write<T> withShards(int shards) {
      return toBuilder().shards(shards).build();
    }

    /**
     * An optional function that converts the input entities to a form that can be written into the
     * database.
     */
    public Write<T> withJpaConverter(SerializableFunction<T, Object> jpaConverter) {
      return toBuilder().jpaConverter(jpaConverter).build();
    }

    abstract Builder<T> toBuilder();

    @Override
    public PCollection<Void> expand(PCollection<T> input) {
      return input
          .apply(
              "Shard data " + name(),
              WithKeys.<Integer, T>of(e -> ThreadLocalRandom.current().nextInt(shards()))
                  .withKeyType(integers()))
          // The call to withShardedKey() is performance critical. The resulting transform ensures
          // that data is spread evenly across all worker threads.
          .apply(
              "Group into batches " + name(),
              GroupIntoBatches.<Integer, T>ofSize(batchSize()).withShardedKey())
          .apply(
              "Write in batch for " + name(),
              ParDo.of(new SqlBatchWriter<>(name(), jpaConverter())));
    }

    static <T> Builder<T> builder() {
      return new AutoValue_RegistryJpaIO_Write.Builder<T>()
          .name(DEFAULT_NAME)
          .batchSize(DEFAULT_BATCH_SIZE)
          .shards(DEFAULT_SHARDS)
          .jpaConverter(x -> x);
    }

    @AutoValue.Builder
    abstract static class Builder<T> {

      abstract Builder<T> name(String name);

      abstract Builder<T> batchSize(int batchSize);

      abstract Builder<T> shards(int jdbcNumConnsHint);

      abstract Builder<T> jpaConverter(SerializableFunction<T, Object> jpaConverter);

      abstract Write<T> build();
    }
  }

  /** Writes a batch of entities to a SQL database through a {@link JpaTransactionManager}. */
  private static class SqlBatchWriter<T> extends DoFn<KV<ShardedKey<Integer>, Iterable<T>>, Void> {
    private final Counter counter;
    private final SerializableFunction<T, Object> jpaConverter;

    SqlBatchWriter(String type, SerializableFunction<T, Object> jpaConverter) {
      counter = Metrics.counter("SQL_WRITE", type);
      this.jpaConverter = jpaConverter;
    }

    @Setup
    public void setup() {
      // AppEngineEnvironment is needed as long as Objectify keys are still involved in the handling
      // of SQL entities (e.g., in VKeys). ObjectifyService needs to be initialized when conversion
      // between Ofy entity and Datastore entity is needed.
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ObjectifyService.initOfy();
      }
    }

    @ProcessElement
    public void processElement(@Element KV<ShardedKey<Integer>, Iterable<T>> kv) {
      try (AppEngineEnvironment env = new AppEngineEnvironment()) {
        ImmutableList<Object> ofyEntities =
            Streams.stream(kv.getValue())
                .map(this.jpaConverter::apply)
                // TODO(b/177340730): post migration delete the line below.
                .filter(Objects::nonNull)
                .collect(ImmutableList.toImmutableList());
        try {
          jpaTm().transact(() -> jpaTm().putAll(ofyEntities));
          counter.inc(ofyEntities.size());
        } catch (RuntimeException e) {
          processSingly(ofyEntities);
        }
      }
    }

    /**
     * Writes entities in a failed batch one by one to identify the first bad entity and throws a
     * {@link RuntimeException} on it.
     */
    private void processSingly(ImmutableList<Object> ofyEntities) {
      for (Object ofyEntity : ofyEntities) {
        try {
          jpaTm().transact(() -> jpaTm().put(ofyEntity));
          counter.inc();
        } catch (RuntimeException e) {
          throw new RuntimeException(toOfyKey(ofyEntity).toString(), e);
        }
      }
    }

    private com.googlecode.objectify.Key<?> toOfyKey(Object ofyEntity) {
      return com.googlecode.objectify.Key.create(ofyEntity);
    }
  }
}
