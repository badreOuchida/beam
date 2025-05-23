/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.gcp.datastore;

import static com.google.datastore.v1.PropertyFilter.Operator.EQUAL;
import static com.google.datastore.v1.PropertyOrder.Direction.DESCENDING;
import static com.google.datastore.v1.QueryResultBatch.MoreResultsType.NOT_FINISHED;
import static com.google.datastore.v1.client.DatastoreHelper.makeAndFilter;
import static com.google.datastore.v1.client.DatastoreHelper.makeDelete;
import static com.google.datastore.v1.client.DatastoreHelper.makeFilter;
import static com.google.datastore.v1.client.DatastoreHelper.makeOrder;
import static com.google.datastore.v1.client.DatastoreHelper.makeUpsert;
import static com.google.datastore.v1.client.DatastoreHelper.makeValue;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkArgument;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkNotNull;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Verify.verify;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.auth.Credentials;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auto.value.AutoValue;
import com.google.cloud.hadoop.util.ChainingHttpRequestInitializer;
import com.google.datastore.v1.CommitRequest;
import com.google.datastore.v1.CommitResponse;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.EntityResult;
import com.google.datastore.v1.GqlQuery;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.Key.PathElement;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.QueryResultBatch;
import com.google.datastore.v1.ReadOptions;
import com.google.datastore.v1.RunQueryRequest;
import com.google.datastore.v1.RunQueryResponse;
import com.google.datastore.v1.client.Datastore;
import com.google.datastore.v1.client.DatastoreException;
import com.google.datastore.v1.client.DatastoreFactory;
import com.google.datastore.v1.client.DatastoreHelper;
import com.google.datastore.v1.client.DatastoreOptions;
import com.google.datastore.v1.client.QuerySplitter;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.concurrent.Immutable;
import org.apache.beam.runners.core.metrics.GcpResourceIdentifiers;
import org.apache.beam.runners.core.metrics.MonitoringInfoConstants;
import org.apache.beam.runners.core.metrics.ServiceCallMetric;
import org.apache.beam.sdk.PipelineRunner;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.extensions.gcp.util.RetryHttpRequestInitializer;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.options.ValueProvider.StaticValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.transforms.display.DisplayData;
import org.apache.beam.sdk.transforms.display.HasDisplayData;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.util.BackOff;
import org.apache.beam.sdk.util.BackOffUtils;
import org.apache.beam.sdk.util.FluentBackoff;
import org.apache.beam.sdk.util.Sleeper;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.MoreObjects;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableSet;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Iterables;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DatastoreV1} provides an API to Read, Write and Delete {@link PCollection PCollections} of
 * <a href="https://developers.google.com/datastore/">Google Cloud Datastore</a> version v1 {@link
 * Entity} objects. Read is only supported for Bounded PCollections while Write and Delete are
 * supported for both Bounded and Unbounded PCollections.
 *
 * <p>This API currently requires an authentication workaround. To use {@link DatastoreV1}, users
 * must use the {@code gcloud} command line tool to get credentials for Cloud Datastore:
 *
 * <pre>
 * $ gcloud auth login
 * </pre>
 *
 * <p>To read a {@link PCollection} from a query to Cloud Datastore, use {@link DatastoreV1#read}
 * and its methods {@link DatastoreV1.Read#withProjectId} and {@link DatastoreV1.Read#withQuery} to
 * specify the project to query and the query to read from. You can optionally provide a namespace
 * to query within using {@link DatastoreV1.Read#withDatabaseId} or {@link
 * DatastoreV1.Read#withNamespace}. You could also optionally specify how many splits you want for
 * the query using {@link DatastoreV1.Read#withNumQuerySplits}.
 *
 * <p>For example:
 *
 * <pre>{@code
 * // Read a query from Datastore
 * PipelineOptions options = PipelineOptionsFactory.fromArgs(args).create();
 * Query query = ...;
 * String databaseId = "...";
 * String projectId = "...";
 *
 * Pipeline p = Pipeline.create(options);
 * PCollection<Entity> entities = p.apply(
 *     DatastoreIO.v1().read()
 *         .withProjectId(projectId)
 *         .withDatabaseId(databaseId)
 *         .withQuery(query));
 * }</pre>
 *
 * <p><b>Note:</b> A runner may read from Cloud Datastore in parallel across many workers. However,
 * when the {@link Query} is configured with a limit using {@link
 * com.google.datastore.v1.Query.Builder#setLimit(Int32Value)} or if the Query contains inequality
 * filters like {@code GREATER_THAN, LESS_THAN} etc., then all returned results will be read by a
 * single worker in order to ensure correct data. Since data is read from a single worker, this
 * could have a significant impact on the performance of the job.
 *
 * <p>To write a {@link PCollection} to a Cloud Datastore, use {@link DatastoreV1#write}, specifying
 * the Cloud Datastore project to write to:
 *
 * <pre>{@code
 * PCollection<Entity> entities = ...;
 * entities.apply(DatastoreIO.v1().write().withProjectId(projectId).withDatabaseId(databaseId));
 * p.run();
 * }</pre>
 *
 * <p>To delete a {@link PCollection} of {@link Entity Entities} from Cloud Datastore, use {@link
 * DatastoreV1#deleteEntity()}, specifying the Cloud Datastore project to write to:
 *
 * <pre>{@code
 * PCollection<Entity> entities = ...;
 * entities.apply(DatastoreIO.v1().deleteEntity().withProjectId(projectId).withDatabaseId(databaseId));
 * p.run();
 * }</pre>
 *
 * <p>To delete entities associated with a {@link PCollection} of {@link Key Keys} from Cloud
 * Datastore, use {@link DatastoreV1#deleteKey}, specifying the Cloud Datastore project to write to:
 *
 * <pre>{@code
 * PCollection<Entity> entities = ...;
 * entities.apply(DatastoreIO.v1().deleteKey().withProjectId(projectId).withDatabaseId(databaseId));
 * p.run();
 * }</pre>
 *
 * <p>Write and delete operations will follow a gradual ramp-up by default in order to protect Cloud
 * Datastore from potential overload. This rate limit follows a heuristic based on the expected
 * number of workers. To optimize throughput in this initial stage, you can provide a hint to the
 * relevant {@code PTransform} by calling {@code withHintNumWorkers}, e.g., {@code
 * DatastoreIO.v1().deleteKey().withHintNumWorkers(numWorkers)}. While not recommended, you can also
 * turn this off via {@code .withRampupThrottlingDisabled()}.
 *
 * <p>{@link Entity Entities} in the {@code PCollection} to be written or deleted must have complete
 * {@link Key Keys}. Complete {@code Keys} specify the {@code name} and {@code id} of the {@code
 * Entity}, where incomplete {@code Keys} do not. A {@code namespace} other than {@code projectId}
 * default may be used by specifying it in the {@code Entity} {@code Keys}.
 *
 * <pre>{@code
 * Key.Builder keyBuilder = DatastoreHelper.makeKey(...);
 * keyBuilder.getPartitionIdBuilder().setNamespace(namespace);
 * }</pre>
 *
 * <p>{@code Entities} will be committed as upsert (update or insert) or delete mutations. Please
 * read <a href="https://cloud.google.com/datastore/docs/concepts/entities">Entities, Properties,
 * and Keys</a> for more information about {@code Entity} keys.
 *
 * <h3>Permissions</h3>
 *
 * Permission requirements depend on the {@code PipelineRunner} that is used to execute the
 * pipeline. Please refer to the documentation of corresponding {@code PipelineRunner}s for more
 * details.
 *
 * <p>Please see <a href="https://cloud.google.com/datastore/docs/activate">Cloud Datastore Sign Up
 * </a>for security and permission related information specific to Cloud Datastore.
 *
 * <p>Optionally, Cloud Datastore V1 Emulator, running locally, could be used for testing purposes
 * by providing the host port information through {@code withLocalhost("host:port"} for all the
 * above transforms. In such a case, all the Cloud Datastore API calls are directed to the Emulator.
 *
 * @see PipelineRunner
 *     <h3>Updates to the connector code</h3>
 *     For any updates to this connector, please consider involving corresponding code reviewers
 *     mentioned <a
 *     href="https://github.com/apache/beam/blob/master/sdks/java/io/google-cloud-platform/OWNERS">
 *     here</a>.
 */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class DatastoreV1 {

  // A package-private constructor to prevent direct instantiation from outside of this package
  DatastoreV1() {}

  /**
   * The number of entity updates written per RPC, initially. We buffer updates in the connector and
   * write a batch to Datastore once we have collected a certain number. This is the initial batch
   * size; it is adjusted at runtime based on the performance of previous writes (see {@link
   * DatastoreV1.WriteBatcher}).
   *
   * <p>Testing has found that a batch of 50 entities will generally finish within the timeout even
   * in adverse conditions.
   */
  @VisibleForTesting static final int DATASTORE_BATCH_UPDATE_ENTITIES_START = 50;

  /**
   * When choosing the number of updates in a single RPC, never exceed the maximum allowed by the
   * API.
   */
  @VisibleForTesting static final int DATASTORE_BATCH_UPDATE_ENTITIES_LIMIT = 500;

  /**
   * When choosing the number of updates in a single RPC, do not go below this value. The actual
   * number of entities per request may be lower when we flush for the end of a bundle or if we hit
   * {@link #DATASTORE_BATCH_UPDATE_BYTES_LIMIT}.
   */
  @VisibleForTesting static final int DATASTORE_BATCH_UPDATE_ENTITIES_MIN = 5;

  /**
   * Cloud Datastore has a limit of 10MB per RPC, so we also flush if the total size of mutations
   * exceeds this limit. This is set lower than the 10MB limit on the RPC, as this only accounts for
   * the mutations themselves and not the CommitRequest wrapper around them.
   */
  @VisibleForTesting static final int DATASTORE_BATCH_UPDATE_BYTES_LIMIT = 9_000_000;

  /**
   * Default hint for the expected number of workers in the ramp-up throttling step. This is used to
   * approximate a global rate limit, so a mismatch can yield slightly elevated throttling.
   * Reconfigure on the {@link Mutate} object to align with the worker count and improve throughput.
   */
  private static final int DEFAULT_HINT_NUM_WORKERS = 500;

  /**
   * Non-retryable errors. See https://cloud.google.com/datastore/docs/concepts/errors#Error_Codes.
   */
  private static final Set<Code> NON_RETRYABLE_ERRORS =
      ImmutableSet.of(
          Code.FAILED_PRECONDITION,
          Code.INVALID_ARGUMENT,
          Code.PERMISSION_DENIED,
          Code.UNAUTHENTICATED);

  /** Database ID for the default database. */
  private static final String DEFAULT_DATABASE = "";

  /**
   * Returns an empty {@link DatastoreV1.Read} builder. Configure the source {@code projectId},
   * {@code query}, and optionally {@code namespace} and {@code numQuerySplits} using {@link
   * DatastoreV1.Read#withProjectId}, {@link DatastoreV1.Read#withQuery}, {@link
   * DatastoreV1.Read#withNamespace}, {@link DatastoreV1.Read#withNumQuerySplits}.
   */
  public DatastoreV1.Read read() {
    return new AutoValue_DatastoreV1_Read.Builder().setNumQuerySplits(0).build();
  }

  /**
   * A {@link PTransform} that reads the result rows of a Cloud Datastore query as {@code Entity}
   * objects.
   *
   * @see DatastoreIO
   */
  @AutoValue
  public abstract static class Read extends PTransform<PBegin, PCollection<Entity>> {
    private static final Logger LOG = LoggerFactory.getLogger(Read.class);

    /** An upper bound on the number of splits for a query. */
    public static final int NUM_QUERY_SPLITS_MAX = 50000;

    /** A lower bound on the number of splits for a query. */
    static final int NUM_QUERY_SPLITS_MIN = 12;

    /** Default bundle size of 64MB. */
    static final long DEFAULT_BUNDLE_SIZE_BYTES = 64L * 1024L * 1024L;

    /**
     * Maximum number of results to request per query.
     *
     * <p>Must be set, or it may result in an I/O error when querying Cloud Datastore.
     */
    static final int QUERY_BATCH_LIMIT = 500;

    public abstract @Nullable ValueProvider<String> getProjectId();

    public abstract @Nullable ValueProvider<String> getDatabaseId();

    public abstract @Nullable Query getQuery();

    public abstract @Nullable ValueProvider<String> getLiteralGqlQuery();

    public abstract @Nullable ValueProvider<String> getNamespace();

    public abstract int getNumQuerySplits();

    public abstract @Nullable String getLocalhost();

    public abstract @Nullable Instant getReadTime();

    @Override
    public abstract String toString();

    abstract Builder toBuilder();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Builder setProjectId(ValueProvider<String> projectId);

      abstract Builder setDatabaseId(ValueProvider<String> databaseId);

      abstract Builder setQuery(Query query);

      abstract Builder setLiteralGqlQuery(ValueProvider<String> literalGqlQuery);

      abstract Builder setNamespace(ValueProvider<String> namespace);

      abstract Builder setNumQuerySplits(int numQuerySplits);

      abstract Builder setLocalhost(String localhost);

      abstract Builder setReadTime(Instant readTime);

      abstract Read build();
    }

    /**
     * Computes the number of splits to be performed on the given query by querying the estimated
     * size from Cloud Datastore.
     */
    static int getEstimatedNumSplits(
        Datastore datastore,
        String projectId,
        String databaseId,
        Query query,
        @Nullable String namespace,
        @Nullable Instant readTime) {
      int numSplits;
      try {
        long estimatedSizeBytes =
            getEstimatedSizeBytes(datastore, projectId, databaseId, query, namespace, readTime);
        LOG.info("Estimated size bytes for the query is: {}", estimatedSizeBytes);
        numSplits =
            (int)
                Math.min(
                    NUM_QUERY_SPLITS_MAX,
                    Math.round(((double) estimatedSizeBytes) / DEFAULT_BUNDLE_SIZE_BYTES));
      } catch (Exception e) {
        LOG.warn("Failed the fetch estimatedSizeBytes for query: {}", query, e);
        // Fallback in case estimated size is unavailable.
        numSplits = NUM_QUERY_SPLITS_MIN;
      }
      return Math.max(numSplits, NUM_QUERY_SPLITS_MIN);
    }

    /**
     * Cloud Datastore system tables with statistics are periodically updated. This method fetches
     * the latest timestamp (in microseconds) of statistics update using the {@code __Stat_Total__}
     * table.
     */
    private static long queryLatestStatisticsTimestamp(
        Datastore datastore,
        String projectId,
        String databaseId,
        @Nullable String namespace,
        @Nullable Instant readTime)
        throws DatastoreException {
      Query.Builder query = Query.newBuilder();
      // Note: namespace either being null or empty represents the default namespace, in which
      // case we treat it as not provided by the user.
      if (Strings.isNullOrEmpty(namespace)) {
        query.addKindBuilder().setName("__Stat_Total__");
      } else {
        query.addKindBuilder().setName("__Stat_Ns_Total__");
      }
      query.addOrder(makeOrder("timestamp", DESCENDING));
      query.setLimit(Int32Value.newBuilder().setValue(1));
      RunQueryRequest request =
          makeRequest(projectId, databaseId, query.build(), namespace, readTime);

      RunQueryResponse response = datastore.runQuery(request);
      QueryResultBatch batch = response.getBatch();
      if (batch.getEntityResultsCount() == 0) {
        throw new NoSuchElementException("Datastore total statistics unavailable");
      }
      Entity entity = batch.getEntityResults(0).getEntity();
      return entity.getPropertiesOrThrow("timestamp").getTimestampValue().getSeconds() * 1000000;
    }

    /**
     * Retrieve latest table statistics for a given kind, namespace, and datastore. If the Read has
     * readTime specified, the latest statistics at or before readTime is retrieved.
     */
    private static Entity getLatestTableStats(
        String projectId,
        String databaseId,
        String ourKind,
        @Nullable String namespace,
        Datastore datastore,
        @Nullable Instant readTime)
        throws DatastoreException {
      long latestTimestamp =
          queryLatestStatisticsTimestamp(datastore, projectId, databaseId, namespace, readTime);
      LOG.info("Latest stats timestamp for kind {} is {}", ourKind, latestTimestamp);

      Query.Builder queryBuilder = Query.newBuilder();
      if (Strings.isNullOrEmpty(namespace)) {
        queryBuilder.addKindBuilder().setName("__Stat_Kind__");
      } else {
        queryBuilder.addKindBuilder().setName("__Stat_Ns_Kind__");
      }

      queryBuilder.setFilter(
          makeAndFilter(
              makeFilter("kind_name", EQUAL, makeValue(ourKind).build()).build(),
              makeFilter("timestamp", EQUAL, makeValue(latestTimestamp).build()).build()));

      RunQueryRequest request =
          makeRequest(projectId, databaseId, queryBuilder.build(), namespace, readTime);

      long now = System.currentTimeMillis();
      RunQueryResponse response = datastore.runQuery(request);
      LOG.debug("Query for per-kind statistics took {}ms", System.currentTimeMillis() - now);

      QueryResultBatch batch = response.getBatch();
      if (batch.getEntityResultsCount() == 0) {
        throw new NoSuchElementException(
            "Datastore statistics for kind " + ourKind + " unavailable");
      }
      return batch.getEntityResults(0).getEntity();
    }

    /**
     * Get the estimated size of the data returned by the given query.
     *
     * <p>Cloud Datastore provides no way to get a good estimate of how large the result of a query
     * entity kind being queried, using the __Stat_Kind__ system table, assuming exactly 1 kind is
     * specified in the query.
     *
     * <p>See https://cloud.google.com/datastore/docs/concepts/stats.
     */
    static long getEstimatedSizeBytes(
        Datastore datastore,
        String projectId,
        String databaseId,
        Query query,
        @Nullable String namespace,
        @Nullable Instant readTime)
        throws DatastoreException {
      String ourKind = query.getKind(0).getName();
      Entity entity =
          getLatestTableStats(projectId, databaseId, ourKind, namespace, datastore, readTime);
      return entity.getPropertiesOrThrow("entity_bytes").getIntegerValue();
    }

    private static PartitionId.Builder forNamespace(@Nullable String namespace) {
      PartitionId.Builder partitionBuilder = PartitionId.newBuilder();
      // Namespace either being null or empty represents the default namespace.
      // Datastore Client libraries expect users to not set the namespace proto field in
      // either of these cases.
      if (!Strings.isNullOrEmpty(namespace)) {
        partitionBuilder.setNamespaceId(namespace);
      }
      return partitionBuilder;
    }

    /**
     * Builds a {@link RunQueryRequest} from the {@code query} and {@code namespace}, optionally at
     * the requested {@code readTime}.
     */
    static RunQueryRequest makeRequest(
        String projectId,
        String databaseId,
        Query query,
        @Nullable String namespace,
        @Nullable Instant readTime) {
      RunQueryRequest.Builder request =
          RunQueryRequest.newBuilder()
              .setProjectId(projectId)
              .setDatabaseId(databaseId)
              .setQuery(query)
              .setPartitionId(
                  forNamespace(namespace).setProjectId(projectId).setDatabaseId(databaseId));
      if (readTime != null) {
        Timestamp readTimeProto = Timestamps.fromMillis(readTime.getMillis());
        request.setReadOptions(ReadOptions.newBuilder().setReadTime(readTimeProto).build());
      }
      return request.build();
    }

    @VisibleForTesting
    /**
     * Builds a {@link RunQueryRequest} from the {@code GqlQuery} and {@code namespace}, optionally
     * at the requested {@code readTime}.
     */
    static RunQueryRequest makeRequest(
        String projectId,
        String databaseId,
        GqlQuery gqlQuery,
        @Nullable String namespace,
        @Nullable Instant readTime) {
      RunQueryRequest.Builder request =
          RunQueryRequest.newBuilder()
              .setProjectId(projectId)
              .setDatabaseId(databaseId)
              .setGqlQuery(gqlQuery)
              .setPartitionId(
                  forNamespace(namespace).setProjectId(projectId).setDatabaseId(databaseId));
      if (readTime != null) {
        Timestamp readTimeProto = Timestamps.fromMillis(readTime.getMillis());
        request.setReadOptions(ReadOptions.newBuilder().setReadTime(readTimeProto).build());
      }

      return request.build();
    }

    /**
     * A helper function to get the split queries, taking into account the optional {@code
     * namespace}.
     */
    private static List<Query> splitQuery(
        String projectId,
        String databaseId,
        Query query,
        @Nullable String namespace,
        Datastore datastore,
        QuerySplitter querySplitter,
        int numSplits,
        @Nullable Instant readTime)
        throws DatastoreException {
      // If namespace is set, include it in the split request so splits are calculated accordingly.
      PartitionId partitionId =
          forNamespace(namespace).setProjectId(projectId).setDatabaseId(databaseId).build();
      if (readTime != null) {
        Timestamp readTimeProto = Timestamps.fromMillis(readTime.getMillis());
        return querySplitter.getSplits(query, partitionId, numSplits, datastore, readTimeProto);
      }
      return querySplitter.getSplits(query, partitionId, numSplits, datastore);
    }

    /**
     * Translates a Cloud Datastore gql query string to {@link Query}.
     *
     * <p>Currently, the only way to translate a gql query string to a Query is to run the query
     * against Cloud Datastore and extract the {@code Query} from the response. To prevent reading
     * any data, we set the {@code LIMIT} to 0 but if the gql query already has a limit set, we
     * catch the exception with {@code INVALID_ARGUMENT} error code and retry the translation
     * without the zero limit.
     *
     * <p>Note: This may result in reading actual data from Cloud Datastore but the service has a
     * cap on the number of entities returned for a single rpc request, so this should not be a
     * problem in practice.
     */
    @VisibleForTesting
    static Query translateGqlQueryWithLimitCheck(
        String gql,
        Datastore datastore,
        String projectId,
        String databaseId,
        String namespace,
        @Nullable Instant readTime)
        throws DatastoreException {
      String gqlQueryWithZeroLimit = gql + " LIMIT 0";
      try {
        Query translatedQuery =
            translateGqlQuery(
                gqlQueryWithZeroLimit, datastore, projectId, databaseId, namespace, readTime);
        // Clear the limit that we set.
        return translatedQuery.toBuilder().clearLimit().build();
      } catch (DatastoreException e) {
        // Note: There is no specific error code or message to detect if the query already has a
        // limit, so we just check for INVALID_ARGUMENT and assume that that the query might have
        // a limit already set.
        if (e.getCode() == Code.INVALID_ARGUMENT) {
          LOG.warn("Failed to translate Gql query '{}': {}", gqlQueryWithZeroLimit, e.getMessage());
          LOG.warn("User query might have a limit already set, so trying without zero limit");
          // Retry without the zero limit.
          return translateGqlQuery(gql, datastore, projectId, databaseId, namespace, readTime);
        } else {
          throw e;
        }
      }
    }

    /** Translates a gql query string to {@link Query}. */
    private static Query translateGqlQuery(
        String gql,
        Datastore datastore,
        String projectId,
        String databaseId,
        String namespace,
        @Nullable Instant readTime)
        throws DatastoreException {
      GqlQuery gqlQuery = GqlQuery.newBuilder().setQueryString(gql).setAllowLiterals(true).build();
      RunQueryRequest req = makeRequest(projectId, databaseId, gqlQuery, namespace, readTime);
      return datastore.runQuery(req).getQuery();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads from the Cloud Datastore for the specified
     * database.
     */
    public DatastoreV1.Read withDatabaseId(String databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return toBuilder().setDatabaseId(StaticValueProvider.of(databaseId)).build();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads from the Cloud Datastore for the specified
     * project.
     */
    public DatastoreV1.Read withProjectId(String projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return toBuilder().setProjectId(StaticValueProvider.of(projectId)).build();
    }

    /** Same as {@link Read#withProjectId(String)} but with a {@link ValueProvider}. */
    public DatastoreV1.Read withProjectId(ValueProvider<String> projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return toBuilder().setProjectId(projectId).build();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads the results of the specified query.
     *
     * <p><b>Note:</b> Normally, {@code DatastoreIO} will read from Cloud Datastore in parallel
     * across many workers. However, when the {@link Query} is configured with a limit using {@link
     * Query.Builder#setLimit}, then all results will be read by a single worker in order to ensure
     * correct results.
     */
    public DatastoreV1.Read withQuery(Query query) {
      checkArgument(query != null, "query can not be null");
      checkArgument(
          !query.hasLimit() || query.getLimit().getValue() > 0,
          "Invalid query limit %s: must be positive",
          query.getLimit().getValue());
      return toBuilder().setQuery(query).build();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads the results of the specified GQL query. See
     * <a href="https://cloud.google.com/datastore/docs/reference/gql_reference">GQL Reference </a>
     * to know more about GQL grammar.
     *
     * <p><b><i>Note:</i></b> This query is executed with literals allowed, so the users should
     * ensure that the query is originated from trusted sources to avoid any security
     * vulnerabilities via SQL Injection.
     *
     * <p>Cloud Datastore does not a provide a clean way to translate a gql query string to {@link
     * Query}, so we end up making a query to the service for translation but this may read the
     * actual data, although it will be a small amount. It needs more validation through production
     * use cases before marking it as stable.
     */
    public DatastoreV1.Read withLiteralGqlQuery(String gqlQuery) {
      checkArgument(gqlQuery != null, "gqlQuery can not be null");
      return toBuilder().setLiteralGqlQuery(StaticValueProvider.of(gqlQuery)).build();
    }

    /** Same as {@link Read#withLiteralGqlQuery(String)} but with a {@link ValueProvider}. */
    public DatastoreV1.Read withLiteralGqlQuery(ValueProvider<String> gqlQuery) {
      checkArgument(gqlQuery != null, "gqlQuery can not be null");
      if (gqlQuery.isAccessible()) {
        checkArgument(gqlQuery.get() != null, "gqlQuery can not be null");
      }
      return toBuilder().setLiteralGqlQuery(gqlQuery).build();
    }

    /** Returns a new {@link DatastoreV1.Read} that reads from the given namespace. */
    public DatastoreV1.Read withNamespace(String namespace) {
      return toBuilder().setNamespace(StaticValueProvider.of(namespace)).build();
    }

    /** Same as {@link Read#withNamespace(String)} but with a {@link ValueProvider}. */
    public DatastoreV1.Read withNamespace(ValueProvider<String> namespace) {
      return toBuilder().setNamespace(namespace).build();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads by splitting the given {@code query} into
     * {@code numQuerySplits}.
     *
     * <p>The semantics for the query splitting is defined below:
     *
     * <ul>
     *   <li>Any value less than or equal to 0 will be ignored, and the number of splits will be
     *       chosen dynamically at runtime based on the query data size.
     *   <li>Any value greater than {@link Read#NUM_QUERY_SPLITS_MAX} will be capped at {@code
     *       NUM_QUERY_SPLITS_MAX}.
     *   <li>If the {@code query} has a user limit set, or contains inequality filters, then {@code
     *       numQuerySplits} will be ignored and no split will be performed.
     *   <li>Under certain cases Cloud Datastore is unable to split query to the requested number of
     *       splits. In such cases we just use whatever the Cloud Datastore returns.
     * </ul>
     */
    public DatastoreV1.Read withNumQuerySplits(int numQuerySplits) {
      return toBuilder()
          .setNumQuerySplits(Math.min(Math.max(numQuerySplits, 0), NUM_QUERY_SPLITS_MAX))
          .build();
    }

    /**
     * Returns a new {@link DatastoreV1.Read} that reads from a Datastore Emulator running at the
     * given localhost address.
     */
    public DatastoreV1.Read withLocalhost(String localhost) {
      return toBuilder().setLocalhost(localhost).build();
    }

    /** Returns a new {@link DatastoreV1.Read} that reads at the specified {@code readTime}. */
    public DatastoreV1.Read withReadTime(Instant readTime) {
      return toBuilder().setReadTime(readTime).build();
    }

    /** Returns Number of entities available for reading. */
    public long getNumEntities(
        PipelineOptions options, String ourKind, @Nullable String namespace) {
      try {
        V1Options v1Options =
            V1Options.from(getProjectId(), getDatabaseId(), getNamespace(), getLocalhost());
        V1DatastoreFactory datastoreFactory = new V1DatastoreFactory();
        Datastore datastore =
            datastoreFactory.getDatastore(
                options,
                v1Options.getProjectId(),
                v1Options.getDatabaseId(),
                v1Options.getLocalhost());

        Entity entity =
            getLatestTableStats(
                v1Options.getProjectId(),
                v1Options.getDatabaseId(),
                ourKind,
                namespace,
                datastore,
                getReadTime());
        return entity.getPropertiesOrThrow("count").getIntegerValue();
      } catch (Exception e) {
        return -1;
      }
    }

    @Override
    public PCollection<Entity> expand(PBegin input) {
      checkArgument(getProjectId() != null, "projectId provider cannot be null");
      if (getProjectId().isAccessible()) {
        checkArgument(getProjectId().get() != null, "projectId cannot be null");
      }

      checkArgument(
          getQuery() != null || getLiteralGqlQuery() != null,
          "Either withQuery() or withLiteralGqlQuery() is required");
      checkArgument(
          getQuery() == null || getLiteralGqlQuery() == null,
          "withQuery() and withLiteralGqlQuery() are exclusive");

      V1Options v1Options =
          V1Options.from(getProjectId(), getDatabaseId(), getNamespace(), getLocalhost());

      /*
       * This composite transform involves the following steps:
       *   1. Create a singleton of the user provided {@code query} or if {@code gqlQuery} is
       *   provided apply a {@link ParDo} that translates the {@code gqlQuery} into a {@code query}.
       *
       *   2. A {@link ParDo} splits the resulting query into {@code numQuerySplits} and
       *   assign each split query a unique {@code Integer} as the key. The resulting output is
       *   of the type {@code PCollection<KV<Integer, Query>>}.
       *
       *   If the value of {@code numQuerySplits} is less than or equal to 0, then the number of
       *   splits will be computed dynamically based on the size of the data for the {@code query}.
       *
       *   3. The resulting {@code PCollection} is sharded using a {@link GroupByKey} operation. The
       *   queries are extracted from they {@code KV<Integer, Iterable<Query>>} and flattened to
       *   output a {@code PCollection<Query>}.
       *
       *   4. In the third step, a {@code ParDo} reads entities for each query and outputs
       *   a {@code PCollection<Entity>}.
       */

      PCollection<Query> inputQuery;
      if (getQuery() != null) {
        inputQuery = input.apply(Create.of(getQuery()));
      } else {
        inputQuery =
            input
                .apply(Create.ofProvider(getLiteralGqlQuery(), StringUtf8Coder.of()))
                .apply(ParDo.of(new GqlQueryTranslateFn(v1Options, getReadTime())));
      }

      return inputQuery
          .apply("Split", ParDo.of(new SplitQueryFn(v1Options, getNumQuerySplits(), getReadTime())))
          .apply("Reshuffle", Reshuffle.viaRandomKey())
          .apply("Read", ParDo.of(new ReadFn(v1Options, getReadTime())));
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      String query = getQuery() == null ? null : getQuery().toString();
      builder
          .addIfNotNull(DisplayData.item("projectId", getProjectId()).withLabel("ProjectId"))
          .addIfNotNull(DisplayData.item("databaseId", getDatabaseId()).withLabel("DatabaseId"))
          .addIfNotNull(DisplayData.item("namespace", getNamespace()).withLabel("Namespace"))
          .addIfNotNull(DisplayData.item("query", query).withLabel("Query"))
          .addIfNotNull(DisplayData.item("gqlQuery", getLiteralGqlQuery()).withLabel("GqlQuery"))
          .addIfNotNull(DisplayData.item("readTime", getReadTime()).withLabel("ReadTime"));
    }

    @VisibleForTesting
    static class V1Options implements HasDisplayData, Serializable {
      private final ValueProvider<String> project;
      private final ValueProvider<String> database;
      private final @Nullable ValueProvider<String> namespace;
      private final @Nullable String localhost;

      private V1Options(
          ValueProvider<String> project,
          ValueProvider<String> database,
          ValueProvider<String> namespace,
          String localhost) {
        this.project = project;
        this.database = database;
        this.namespace = namespace;
        this.localhost = localhost;
      }

      public static V1Options from(
          String projectId, ValueProvider<String> databaseId, String namespace, String localhost) {
        return from(
            StaticValueProvider.of(projectId),
            databaseId,
            StaticValueProvider.of(namespace),
            localhost);
      }

      public static V1Options from(
          ValueProvider<String> project,
          ValueProvider<String> databaseId,
          ValueProvider<String> namespace,
          String localhost) {
        return new V1Options(project, databaseId, namespace, localhost);
      }

      public String getProjectId() {
        return project.get();
      }

      public String getDatabaseId() {
        return database == null ? DEFAULT_DATABASE : database.get();
      }

      public @Nullable String getNamespace() {
        return namespace == null ? null : namespace.get();
      }

      public ValueProvider<String> getProjectValueProvider() {
        return project;
      }

      public ValueProvider<String> getDatabaseValueProvider() {
        return database;
      }

      public @Nullable ValueProvider<String> getNamespaceValueProvider() {
        return namespace;
      }

      public @Nullable String getLocalhost() {
        return localhost;
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        builder
            .addIfNotNull(
                DisplayData.item("projectId", getProjectValueProvider()).withLabel("ProjectId"))
            .addIfNotNull(
                DisplayData.item("databaseId", getDatabaseValueProvider()).withLabel("DatabaseId"))
            .addIfNotNull(
                DisplayData.item("namespace", getNamespaceValueProvider()).withLabel("Namespace"));
      }
    }

    /** A DoFn that translates a Cloud Datastore gql query string to {@code Query}. */
    static class GqlQueryTranslateFn extends DoFn<String, Query> {
      private final V1Options v1Options;
      private final @Nullable Instant readTime;
      private transient Datastore datastore;
      private final V1DatastoreFactory datastoreFactory;

      GqlQueryTranslateFn(V1Options options) {
        this(options, null, new V1DatastoreFactory());
      }

      GqlQueryTranslateFn(V1Options options, @Nullable Instant readTime) {
        this(options, readTime, new V1DatastoreFactory());
      }

      GqlQueryTranslateFn(
          V1Options options, @Nullable Instant readTime, V1DatastoreFactory datastoreFactory) {
        this.v1Options = options;
        this.readTime = readTime;
        this.datastoreFactory = datastoreFactory;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) throws Exception {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(),
                v1Options.getProjectId(),
                v1Options.getDatabaseId(),
                v1Options.getLocalhost());
      }

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        String gqlQuery = c.element();
        LOG.info("User query: '{}'", gqlQuery);
        Query query =
            translateGqlQueryWithLimitCheck(
                gqlQuery,
                datastore,
                v1Options.getProjectId(),
                v1Options.getDatabaseId(),
                v1Options.getNamespace(),
                readTime);
        LOG.info("User gql query translated to Query({})", query);
        c.output(query);
      }
    }

    /**
     * A {@link DoFn} that splits a given query into multiple sub-queries, assigns them unique keys
     * and outputs them as {@link KV}.
     */
    @VisibleForTesting
    static class SplitQueryFn extends DoFn<Query, Query> {
      private final V1Options options;
      // number of splits to make for a given query
      private final int numSplits;
      // time from which to run the queries
      private final @Nullable Instant readTime;

      private final V1DatastoreFactory datastoreFactory;
      // Datastore client
      private transient Datastore datastore;
      // Query splitter
      private transient QuerySplitter querySplitter;

      public SplitQueryFn(V1Options options, int numSplits) {
        this(options, numSplits, null, new V1DatastoreFactory());
      }

      public SplitQueryFn(V1Options options, int numSplits, @Nullable Instant readTime) {
        this(options, numSplits, readTime, new V1DatastoreFactory());
      }

      @VisibleForTesting
      SplitQueryFn(
          V1Options options,
          int numSplits,
          @Nullable Instant readTime,
          V1DatastoreFactory datastoreFactory) {
        this.options = options;
        this.numSplits = numSplits;
        this.datastoreFactory = datastoreFactory;
        this.readTime = readTime;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) throws Exception {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(),
                options.getProjectId(),
                options.getDatabaseId(),
                options.getLocalhost());
        querySplitter = datastoreFactory.getQuerySplitter();
      }

      @ProcessElement
      public void processElement(ProcessContext c) throws Exception {
        Query query = c.element();

        // If query has a user set limit, then do not split.
        if (query.hasLimit()) {
          c.output(query);
          return;
        }

        int estimatedNumSplits;
        // Compute the estimated numSplits if numSplits is not specified by the user.
        if (numSplits <= 0) {
          estimatedNumSplits =
              getEstimatedNumSplits(
                  datastore,
                  options.getProjectId(),
                  options.getDatabaseId(),
                  query,
                  options.getNamespace(),
                  readTime);
        } else {
          estimatedNumSplits = numSplits;
        }

        LOG.info("Splitting the query into {} splits", estimatedNumSplits);
        List<Query> querySplits;
        try {
          querySplits =
              splitQuery(
                  options.getProjectId(),
                  options.getDatabaseId(),
                  query,
                  options.getNamespace(),
                  datastore,
                  querySplitter,
                  estimatedNumSplits,
                  readTime);
        } catch (Exception e) {
          LOG.warn("Unable to parallelize the given query: {}", query, e);
          querySplits = ImmutableList.of(query);
        }

        // assign unique keys to query splits.
        for (Query subquery : querySplits) {
          c.output(subquery);
        }
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        super.populateDisplayData(builder);
        builder.include("options", options);
        if (numSplits > 0) {
          builder.add(
              DisplayData.item("numQuerySplits", numSplits)
                  .withLabel("Requested number of Query splits"));
        }
        builder.addIfNotNull(DisplayData.item("readTime", readTime).withLabel("ReadTime"));
      }
    }

    /** A {@link DoFn} that reads entities from Cloud Datastore for each query. */
    @VisibleForTesting
    static class ReadFn extends DoFn<Query, Entity> {
      private final V1Options options;
      private final @Nullable Instant readTime;
      private final V1DatastoreFactory datastoreFactory;
      // Datastore client
      private transient Datastore datastore;
      private final Counter rpcErrors =
          Metrics.counter(DatastoreWriterFn.class, "datastoreRpcErrors");
      private final Counter rpcSuccesses =
          Metrics.counter(DatastoreWriterFn.class, "datastoreRpcSuccesses");
      private static final int MAX_RETRIES = 5;
      private static final FluentBackoff RUNQUERY_BACKOFF =
          FluentBackoff.DEFAULT
              .withMaxRetries(MAX_RETRIES)
              .withInitialBackoff(Duration.standardSeconds(5));

      public ReadFn(V1Options options) {
        this(options, null, new V1DatastoreFactory());
      }

      public ReadFn(V1Options options, @Nullable Instant readTime) {
        this(options, readTime, new V1DatastoreFactory());
      }

      @VisibleForTesting
      ReadFn(V1Options options, @Nullable Instant readTime, V1DatastoreFactory datastoreFactory) {
        this.options = options;
        this.readTime = readTime;
        this.datastoreFactory = datastoreFactory;
      }

      @StartBundle
      public void startBundle(StartBundleContext c) throws Exception {
        datastore =
            datastoreFactory.getDatastore(
                c.getPipelineOptions(),
                options.getProjectId(),
                options.getDatabaseId(),
                options.getLocalhost());
      }

      private RunQueryResponse runQueryWithRetries(RunQueryRequest request) throws Exception {
        Sleeper sleeper = Sleeper.DEFAULT;
        BackOff backoff = RUNQUERY_BACKOFF.backoff();
        while (true) {
          HashMap<String, String> baseLabels = new HashMap<>();
          baseLabels.put(MonitoringInfoConstants.Labels.PTRANSFORM, "");
          baseLabels.put(MonitoringInfoConstants.Labels.SERVICE, "Datastore");
          baseLabels.put(MonitoringInfoConstants.Labels.METHOD, "BatchDatastoreRead");
          baseLabels.put(
              MonitoringInfoConstants.Labels.RESOURCE,
              GcpResourceIdentifiers.datastoreResource(
                  options.getProjectId(), options.getNamespace()));
          baseLabels.put(MonitoringInfoConstants.Labels.DATASTORE_PROJECT, options.getProjectId());
          baseLabels.put(
              MonitoringInfoConstants.Labels.DATASTORE_NAMESPACE,
              String.valueOf(options.getNamespace()));
          ServiceCallMetric serviceCallMetric =
              new ServiceCallMetric(MonitoringInfoConstants.Urns.API_REQUEST_COUNT, baseLabels);
          try {
            RunQueryResponse response = datastore.runQuery(request);
            serviceCallMetric.call("ok");
            rpcSuccesses.inc();
            return response;
          } catch (DatastoreException exception) {
            rpcErrors.inc();
            serviceCallMetric.call(exception.getCode().getNumber());

            if (NON_RETRYABLE_ERRORS.contains(exception.getCode())) {
              throw exception;
            }
            if (!BackOffUtils.next(sleeper, backoff)) {
              LOG.error("Aborting after {} retries.", MAX_RETRIES);
              throw exception;
            }
          }
        }
      }

      /** Read and output entities for the given query. */
      @ProcessElement
      public void processElement(ProcessContext context) throws Exception {
        Query query = context.element();
        String namespace = options.getNamespace();
        int userLimit = query.hasLimit() ? query.getLimit().getValue() : Integer.MAX_VALUE;

        boolean moreResults = true;
        QueryResultBatch currentBatch = null;

        while (moreResults) {
          Query.Builder queryBuilder = query.toBuilder();
          queryBuilder.setLimit(
              Int32Value.newBuilder().setValue(Math.min(userLimit, QUERY_BATCH_LIMIT)));

          if (currentBatch != null && !currentBatch.getEndCursor().isEmpty()) {
            queryBuilder.setStartCursor(currentBatch.getEndCursor());
          }

          RunQueryRequest request =
              makeRequest(
                  options.getProjectId(),
                  options.getDatabaseId(),
                  queryBuilder.build(),
                  namespace,
                  readTime);
          RunQueryResponse response = runQueryWithRetries(request);

          currentBatch = response.getBatch();

          // MORE_RESULTS_AFTER_LIMIT is not implemented yet:
          // https://groups.google.com/forum/#!topic/gcd-discuss/iNs6M1jA2Vw, so
          // use result count to determine if more results might exist.
          int numFetch = currentBatch.getEntityResultsCount();
          if (query.hasLimit()) {
            verify(
                userLimit >= numFetch,
                "Expected userLimit %s >= numFetch %s, because query limit %s must be <= userLimit",
                userLimit,
                numFetch,
                query.getLimit());
            userLimit -= numFetch;
          }

          // output all the entities from the current batch.
          for (EntityResult entityResult : currentBatch.getEntityResultsList()) {
            context.output(entityResult.getEntity());
          }

          // Check if we have more entities to be read.
          moreResults =
              // User-limit does not exist (so userLimit == MAX_VALUE) and/or has not been satisfied
              (userLimit > 0)
                  // All indications from the API are that there are/may be more results.
                  && ((numFetch == QUERY_BATCH_LIMIT)
                      || (currentBatch.getMoreResults() == NOT_FINISHED));
        }
      }

      @Override
      public void populateDisplayData(DisplayData.Builder builder) {
        super.populateDisplayData(builder);
        builder.include("options", options);
        builder.addIfNotNull(DisplayData.item("readTime", readTime).withLabel("ReadTime"));
      }
    }
  }

  /**
   * Summary object produced when a number of writes are successfully written to Datastore in a
   * single Mutation.
   */
  @Immutable
  public static final class WriteSuccessSummary implements Serializable {
    private final int numWrites;
    private final long numBytes;

    public WriteSuccessSummary(int numWrites, long numBytes) {
      this.numWrites = numWrites;
      this.numBytes = numBytes;
    }

    public int getNumWrites() {
      return numWrites;
    }

    public long getNumBytes() {
      return numBytes;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof WriteSuccessSummary)) {
        return false;
      }
      WriteSuccessSummary that = (WriteSuccessSummary) o;
      return numWrites == that.numWrites && numBytes == that.numBytes;
    }

    @Override
    public int hashCode() {
      return Objects.hash(numWrites, numBytes);
    }

    @Override
    public String toString() {
      return "WriteSummary{" + "numWrites=" + numWrites + ", numBytes=" + numBytes + '}';
    }
  }

  /**
   * Returns an empty {@link DatastoreV1.Write} builder. Configure the destination {@code projectId}
   * using {@link DatastoreV1.Write#withProjectId}.
   */
  public Write write() {
    return new Write(null, null, true, StaticValueProvider.of(DEFAULT_HINT_NUM_WORKERS));
  }

  /**
   * Returns an empty {@link DeleteEntity} builder. Configure the destination {@code projectId}
   * using {@link DeleteEntity#withProjectId}.
   */
  public DeleteEntity deleteEntity() {
    return new DeleteEntity(null, null, true, StaticValueProvider.of(DEFAULT_HINT_NUM_WORKERS));
  }

  /**
   * Returns an empty {@link DeleteKey} builder. Configure the destination {@code projectId} using
   * {@link DeleteKey#withProjectId}.
   */
  public DeleteKey deleteKey() {
    return new DeleteKey(null, null, true, StaticValueProvider.of(DEFAULT_HINT_NUM_WORKERS));
  }

  /**
   * A {@link PTransform} that writes {@link Entity} objects to Cloud Datastore and returns {@link
   * WriteSuccessSummary} for each successful write.
   *
   * @see DatastoreIO
   */
  public static class WriteWithSummary extends Mutate<Entity> {

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    WriteWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, null, localhost, new UpsertFn(), throttleRampup, hintNumWorkers);
    }

    WriteWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, databaseId, localhost, new UpsertFn(), throttleRampup, hintNumWorkers);
    }

    /**
     * Returns a new {@link WriteWithSummary} that writes to the Cloud Datastore for the default
     * database.
     */
    public WriteWithSummary withProjectId(String projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return withProjectId(StaticValueProvider.of(projectId));
    }

    /**
     * Returns a new {@link WriteWithSummary} that writes to the Cloud Datastore for the database
     * id.
     */
    public WriteWithSummary withDatabaseId(String databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return withDatabaseId(StaticValueProvider.of(databaseId));
    }

    /** Same as {@link WriteWithSummary#withProjectId(String)} but with a {@link ValueProvider}. */
    public WriteWithSummary withProjectId(ValueProvider<String> projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return new WriteWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /** Same as {@link WriteWithSummary#withDatabaseId(String)} but with a {@link ValueProvider}. */
    public WriteWithSummary withDatabaseId(ValueProvider<String> databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return new WriteWithSummary(projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    /**
     * Returns a new {@link WriteWithSummary} that writes to the Cloud Datastore Emulator running
     * locally on the specified host port.
     */
    public WriteWithSummary withLocalhost(String localhost) {
      checkArgument(localhost != null, "localhost can not be null");
      return new WriteWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /** Returns a new {@link WriteWithSummary} that does not throttle during ramp-up. */
    public WriteWithSummary withRampupThrottlingDisabled() {
      return new WriteWithSummary(projectId, localhost, false, hintNumWorkers);
    }

    /**
     * Returns a new {@link WriteWithSummary} with a different worker count hint for ramp-up
     * throttling. Value is ignored if ramp-up throttling is disabled.
     */
    public WriteWithSummary withHintNumWorkers(int hintNumWorkers) {
      return withHintNumWorkers(StaticValueProvider.of(hintNumWorkers));
    }

    /**
     * Same as {@link WriteWithSummary#withHintNumWorkers(int)} but with a {@link ValueProvider}.
     */
    public WriteWithSummary withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      checkArgument(hintNumWorkers != null, "hintNumWorkers can not be null");
      return new WriteWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }
  }

  /**
   * A {@link PTransform} that writes {@link Entity} objects to Cloud Datastore.
   *
   * @see DatastoreIO
   */
  public static class Write extends PTransform<PCollection<Entity>, PDone> {

    WriteWithSummary inner;
    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    Write(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner = new WriteWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    Write(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner =
          new WriteWithSummary(projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    Write(WriteWithSummary inner) {
      this.inner = inner;
    }

    /** Returns a new {@link Write} that writes to the Cloud Datastore for the default database. */
    public Write withProjectId(String projectId) {
      return new Write(this.inner.withProjectId(projectId));
    }

    /** Returns a new {@link Write} that writes to the Cloud Datastore for the database id. */
    public Write withDatabaseId(String databaseId) {
      return new Write(this.inner.withDatabaseId(databaseId));
    }

    /** Same as {@link Write#withProjectId(String)} but with a {@link ValueProvider}. */
    public Write withProjectId(ValueProvider<String> projectId) {
      return new Write(this.inner.withProjectId(projectId));
    }

    /** Same as {@link Write#withDatabaseId(String)} but with a {@link ValueProvider}. */
    public Write withDatabaseId(ValueProvider<String> databaseId) {
      return new Write(this.inner.withDatabaseId(databaseId));
    }

    /**
     * Returns a new {@link Write} that writes to the Cloud Datastore Emulator running locally on
     * the specified host port.
     */
    public Write withLocalhost(String localhost) {
      return new Write(this.inner.withLocalhost(localhost));
    }

    /** Returns a new {@link Write} that does not throttle during ramp-up. */
    public Write withRampupThrottlingDisabled() {
      return new Write(this.inner.withRampupThrottlingDisabled());
    }

    /**
     * Returns a new {@link Write} with a different worker count hint for ramp-up throttling. Value
     * is ignored if ramp-up throttling is disabled.
     */
    public Write withHintNumWorkers(int hintNumWorkers) {
      return new Write(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /** Same as {@link Write#withHintNumWorkers(int)} but with a {@link ValueProvider}. */
    public Write withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      return new Write(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /**
     * Returns {@link WriteWithSummary} transform which can be used in {@link
     * Wait#on(PCollection[])} to wait until all data is written.
     *
     * <p>Example: write a {@link PCollection} to one database and then to another database, making
     * sure that writing a window of data to the second database starts only after the respective
     * window has been fully written to the first database.
     *
     * <pre>{@code
     * PCollection<Entity> entities = ... ;
     * PCollection<DatastoreV1.WriteSuccessSummary> writeSummary =
     *         entities.apply(DatastoreIO.v1().write().withProjectId(project).withResults());
     * }</pre>
     */
    public WriteWithSummary withResults() {
      return inner;
    }

    @Override
    public String toString() {
      return this.inner.toString();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      this.inner.populateDisplayData(builder);
    }

    public String getProjectId() {
      return this.inner.getProjectId();
    }

    public String getDatabaseId() {
      return this.inner.getDatabaseId();
    }

    @Override
    public PDone expand(PCollection<Entity> input) {
      inner.expand(input);
      return PDone.in(input.getPipeline());
    }
  }

  /**
   * A {@link PTransform} that deletes {@link Entity Entities} from Cloud Datastore and returns
   * {@link WriteSuccessSummary} for each successful write.
   *
   * @see DatastoreIO
   */
  public static class DeleteEntityWithSummary extends Mutate<Entity> {

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    DeleteEntityWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, null, localhost, new DeleteEntityFn(), throttleRampup, hintNumWorkers);
    }

    DeleteEntityWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, databaseId, localhost, new DeleteEntityFn(), throttleRampup, hintNumWorkers);
    }

    /**
     * Returns a new {@link DeleteEntityWithSummary} that deletes entities from the Cloud Datastore
     * for the specified project.
     */
    public DeleteEntityWithSummary withProjectId(String projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return withProjectId(StaticValueProvider.of(projectId));
    }

    /**
     * Returns a new {@link DeleteEntityWithSummary} that deletes entities from the Cloud Datastore
     * for the specified database.
     */
    public DeleteEntityWithSummary withDatabaseId(String databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return withDatabaseId(StaticValueProvider.of(databaseId));
    }

    /**
     * Same as {@link DeleteEntityWithSummary#withProjectId(String)} but with a {@link
     * ValueProvider}.
     */
    public DeleteEntityWithSummary withProjectId(ValueProvider<String> projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return new DeleteEntityWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /**
     * Same as {@link DeleteEntityWithSummary#withDatabaseId(String)} but with a {@link
     * ValueProvider}.
     */
    public DeleteEntityWithSummary withDatabaseId(ValueProvider<String> databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return new DeleteEntityWithSummary(
          projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    /**
     * Returns a new {@link DeleteEntityWithSummary} that deletes entities from the Cloud Datastore
     * Emulator running locally on the specified host port.
     */
    public DeleteEntityWithSummary withLocalhost(String localhost) {
      checkArgument(localhost != null, "localhost can not be null");
      return new DeleteEntityWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /** Returns a new {@link DeleteEntityWithSummary} that does not throttle during ramp-up. */
    public DeleteEntityWithSummary withRampupThrottlingDisabled() {
      return new DeleteEntityWithSummary(projectId, localhost, false, hintNumWorkers);
    }

    /**
     * Returns a new {@link DeleteEntityWithSummary} with a different worker count hint for ramp-up
     * throttling. Value is ignored if ramp-up throttling is disabled.
     */
    public DeleteEntityWithSummary withHintNumWorkers(int hintNumWorkers) {
      checkArgument(hintNumWorkers > 0, "hintNumWorkers must be positive");
      return withHintNumWorkers(StaticValueProvider.of(hintNumWorkers));
    }

    /**
     * Same as {@link DeleteEntityWithSummary#withHintNumWorkers(int)} but with a {@link
     * ValueProvider}.
     */
    public DeleteEntityWithSummary withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      checkArgument(hintNumWorkers != null, "hintNumWorkers can not be null");
      return new DeleteEntityWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }
  }

  /**
   * A {@link PTransform} that deletes {@link Entity Entities} from Cloud Datastore.
   *
   * @see DatastoreIO
   */
  public static class DeleteEntity extends PTransform<PCollection<Entity>, PDone> {

    DeleteEntityWithSummary inner;

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    DeleteEntity(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner =
          new DeleteEntityWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    DeleteEntity(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner =
          new DeleteEntityWithSummary(
              projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    DeleteEntity(DeleteEntityWithSummary inner) {
      this.inner = inner;
    }

    /**
     * Returns a new {@link DeleteEntity} that deletes entities from the Cloud Datastore for the
     * specified project.
     */
    public DeleteEntity withProjectId(String projectId) {
      return new DeleteEntity(this.inner.withProjectId(projectId));
    }

    /**
     * Returns a new {@link DeleteEntity} that deletes entities from the Cloud Datastore for the
     * specified database.
     */
    public DeleteEntity withDatabaseId(String databaseId) {
      return new DeleteEntity(this.inner.withDatabaseId(databaseId));
    }

    /** Same as {@link DeleteEntity#withProjectId(String)} but with a {@link ValueProvider}. */
    public DeleteEntity withProjectId(ValueProvider<String> projectId) {
      return new DeleteEntity(this.inner.withProjectId(projectId));
    }

    /** Same as {@link DeleteEntity#withDatabaseId(String)} but with a {@link ValueProvider}. */
    public DeleteEntity withDatabaseId(ValueProvider<String> databaseId) {
      return new DeleteEntity(this.inner.withDatabaseId(databaseId));
    }

    /**
     * Returns a new {@link DeleteEntity} that deletes entities from the Cloud Datastore Emulator
     * running locally on the specified host port.
     */
    public DeleteEntity withLocalhost(String localhost) {
      return new DeleteEntity(this.inner.withLocalhost(localhost));
    }

    /** Returns a new {@link DeleteEntity} that does not throttle during ramp-up. */
    public DeleteEntity withRampupThrottlingDisabled() {
      return new DeleteEntity(this.inner.withRampupThrottlingDisabled());
    }

    /**
     * Returns a new {@link DeleteEntity} with a different worker count hint for ramp-up throttling.
     * Value is ignored if ramp-up throttling is disabled.
     */
    public DeleteEntity withHintNumWorkers(int hintNumWorkers) {
      return new DeleteEntity(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /** Same as {@link DeleteEntity#withHintNumWorkers(int)} but with a {@link ValueProvider}. */
    public DeleteEntity withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      return new DeleteEntity(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /**
     * Returns {@link DeleteEntityWithSummary} transform which can be used in {@link
     * Wait#on(PCollection[])} to wait until all data is deleted.
     *
     * <p>Example: delete a {@link PCollection} from one database and then from another database,
     * making sure that deleting a window of data to the second database starts only after the
     * respective window has been fully deleted from the first database.
     *
     * <pre>{@code
     * PCollection<Entity> entities = ... ;
     * PCollection<DatastoreV1.WriteSuccessSummary> deleteSummary =
     *         entities.apply(DatastoreIO.v1().deleteEntity().withProjectId(project).withResults());
     * }</pre>
     */
    public DeleteEntityWithSummary withResults() {
      return inner;
    }

    @Override
    public String toString() {
      return this.inner.toString();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      this.inner.populateDisplayData(builder);
    }

    public String getProjectId() {
      return this.inner.getProjectId();
    }

    public String getDatabaseId() {
      return this.inner.getDatabaseId();
    }

    @Override
    public PDone expand(PCollection<Entity> input) {
      inner.expand(input);
      return PDone.in(input.getPipeline());
    }
  }

  /**
   * A {@link PTransform} that deletes {@link Entity Entities} associated with the given {@link Key
   * Keys} from Cloud Datastore and returns {@link WriteSuccessSummary} for each successful delete.
   *
   * @see DatastoreIO
   */
  public static class DeleteKeyWithSummary extends Mutate<Key> {

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    DeleteKeyWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, null, localhost, new DeleteKeyFn(), throttleRampup, hintNumWorkers);
    }

    DeleteKeyWithSummary(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      super(projectId, databaseId, localhost, new DeleteKeyFn(), throttleRampup, hintNumWorkers);
    }

    /**
     * Returns a new {@link DeleteKeyWithSummary} that deletes entities from the Cloud Datastore for
     * the specified project.
     */
    public DeleteKeyWithSummary withProjectId(String projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return withProjectId(StaticValueProvider.of(projectId));
    }

    /**
     * Returns a new {@link DeleteKeyWithSummary} that deletes entities from the Cloud Datastore for
     * the specified database.
     */
    public DeleteKeyWithSummary withDatabaseId(String databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return withDatabaseId(StaticValueProvider.of(databaseId));
    }

    /**
     * Returns a new {@link DeleteKeyWithSummary} that deletes entities from the Cloud Datastore
     * Emulator running locally on the specified host port.
     */
    public DeleteKeyWithSummary withLocalhost(String localhost) {
      checkArgument(localhost != null, "localhost can not be null");
      return new DeleteKeyWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /**
     * Same as {@link DeleteKeyWithSummary#withProjectId(String)} but with a {@link ValueProvider}.
     */
    public DeleteKeyWithSummary withProjectId(ValueProvider<String> projectId) {
      checkArgument(projectId != null, "projectId can not be null");
      return new DeleteKeyWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    /**
     * Same as {@link DeleteKeyWithSummary#withDatabaseId(String)} but with a {@link ValueProvider}.
     */
    public DeleteKeyWithSummary withDatabaseId(ValueProvider<String> databaseId) {
      checkArgument(databaseId != null, "databaseId can not be null");
      return new DeleteKeyWithSummary(
          projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    /** Returns a new {@link DeleteKeyWithSummary} that does not throttle during ramp-up. */
    public DeleteKeyWithSummary withRampupThrottlingDisabled() {
      return new DeleteKeyWithSummary(projectId, localhost, false, hintNumWorkers);
    }

    /**
     * Returns a new {@link DeleteKeyWithSummary} with a different worker count hint for ramp-up
     * throttling. Value is ignored if ramp-up throttling is disabled.
     */
    public DeleteKeyWithSummary withHintNumWorkers(int hintNumWorkers) {
      checkArgument(hintNumWorkers > 0, "hintNumWorkers must be positive");
      return withHintNumWorkers(StaticValueProvider.of(hintNumWorkers));
    }

    /**
     * Same as {@link DeleteKeyWithSummary#withHintNumWorkers(int)} but with a {@link
     * ValueProvider}.
     */
    public DeleteKeyWithSummary withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      checkArgument(hintNumWorkers != null, "hintNumWorkers can not be null");
      return new DeleteKeyWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }
  }

  /**
   * A {@link PTransform} that deletes {@link Entity Entities} associated with the given {@link Key
   * Keys} from Cloud Datastore.
   *
   * @see DatastoreIO
   */
  public static class DeleteKey extends PTransform<PCollection<Key>, PDone> {

    DeleteKeyWithSummary inner;

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    DeleteKey(
        @Nullable ValueProvider<String> projectId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner = new DeleteKeyWithSummary(projectId, localhost, throttleRampup, hintNumWorkers);
    }

    DeleteKey(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.inner =
          new DeleteKeyWithSummary(
              projectId, databaseId, localhost, throttleRampup, hintNumWorkers);
    }

    DeleteKey(DeleteKeyWithSummary inner) {
      this.inner = inner;
    }

    /**
     * Returns a new {@link DeleteKey} that deletes entities from the Cloud Datastore for the
     * specified project.
     */
    public DeleteKey withProjectId(String projectId) {
      return new DeleteKey(this.inner.withProjectId(projectId));
    }

    /**
     * Returns a new {@link DeleteKey} that deletes entities from the Cloud Datastore for the
     * specified database.
     */
    public DeleteKey withDatabaseId(String databaseId) {
      return new DeleteKey(this.inner.withDatabaseId(databaseId));
    }

    /**
     * Returns a new {@link DeleteKey} that deletes entities from the Cloud Datastore Emulator
     * running locally on the specified host port.
     */
    public DeleteKey withLocalhost(String localhost) {
      return new DeleteKey(this.inner.withLocalhost(localhost));
    }

    /** Same as {@link DeleteKey#withProjectId(String)} but with a {@link ValueProvider}. */
    public DeleteKey withProjectId(ValueProvider<String> projectId) {
      return new DeleteKey(this.inner.withProjectId(projectId));
    }

    /** Same as {@link DeleteKey#withDatabaseId(String)} but with a {@link ValueProvider}. */
    public DeleteKey withDatabaseId(ValueProvider<String> databaseId) {
      return new DeleteKey(this.inner.withDatabaseId(databaseId));
    }

    /** Returns a new {@link DeleteKey} that does not throttle during ramp-up. */
    public DeleteKey withRampupThrottlingDisabled() {
      return new DeleteKey(this.inner.withRampupThrottlingDisabled());
    }

    /**
     * Returns a new {@link DeleteKey} with a different worker count hint for ramp-up throttling.
     * Value is ignored if ramp-up throttling is disabled.
     */
    public DeleteKey withHintNumWorkers(int hintNumWorkers) {
      return new DeleteKey(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /** Same as {@link DeleteKey#withHintNumWorkers(int)} but with a {@link ValueProvider}. */
    public DeleteKey withHintNumWorkers(ValueProvider<Integer> hintNumWorkers) {
      return new DeleteKey(this.inner.withHintNumWorkers(hintNumWorkers));
    }

    /**
     * Returns {@link DeleteKeyWithSummary} transform which can be used in {@link
     * Wait#on(PCollection[])} to wait until all data is deleted.
     *
     * <p>Example: delete a {@link PCollection} of {@link Key} from one database and then from
     * another database, making sure that deleting a window of data to the second database starts
     * only after the respective window has been fully deleted from the first database.
     */
    public DeleteKeyWithSummary withResults() {
      return inner;
    }

    @Override
    public String toString() {
      return this.inner.toString();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      this.inner.populateDisplayData(builder);
    }

    public String getProjectId() {
      return this.inner.getProjectId();
    }

    public String getDatabaseId() {
      return this.inner.getDatabaseId();
    }

    @Override
    public PDone expand(PCollection<Key> input) {
      inner.expand(input);
      return PDone.in(input.getPipeline());
    }
  }

  /**
   * A {@link PTransform} that writes mutations to Cloud Datastore.
   *
   * <p>It requires a {@link DoFn} that transforms an object of type {@code T} to a {@link
   * Mutation}. {@code T} is usually either an {@link Entity} or a {@link Key} <b>Note:</b> Only
   * idempotent Cloud Datastore mutation operations (upsert and delete) should be used by the {@code
   * DoFn} provided, as the commits are retried when failures occur.
   */
  private abstract static class Mutate<T>
      extends PTransform<PCollection<T>, PCollection<WriteSuccessSummary>> {
    protected ValueProvider<String> projectId;
    protected ValueProvider<String> databaseId;
    protected @Nullable String localhost;
    protected boolean throttleRampup;
    protected ValueProvider<Integer> hintNumWorkers;
    /** A function that transforms each {@code T} into a mutation. */
    private final SimpleFunction<T, Mutation> mutationFn;

    private RampupThrottlingFn<Mutation> rampupThrottlingFn;

    /**
     * Note that {@code projectId} is only {@code @Nullable} as a matter of build order, but if it
     * is {@code null} at instantiation time, an error will be thrown.
     */
    Mutate(
        @Nullable ValueProvider<String> projectId,
        @Nullable ValueProvider<String> databaseId,
        @Nullable String localhost,
        SimpleFunction<T, Mutation> mutationFn,
        boolean throttleRampup,
        ValueProvider<Integer> hintNumWorkers) {
      this.projectId = projectId;
      this.databaseId = databaseId;
      this.localhost = localhost;
      this.throttleRampup = throttleRampup;
      this.hintNumWorkers = hintNumWorkers;
      this.mutationFn = checkNotNull(mutationFn);
    }

    @Override
    public PCollection<WriteSuccessSummary> expand(PCollection<T> input) {
      checkArgument(projectId != null, "withProjectId() is required");
      if (projectId.isAccessible()) {
        checkArgument(projectId.get() != null, "projectId can not be null");
      }
      checkArgument(mutationFn != null, "mutationFn can not be null");

      PCollection<Mutation> intermediateOutput =
          input.apply("Convert to Mutation", MapElements.via(mutationFn));
      if (throttleRampup) {
        PCollectionView<Instant> startTimestampView =
            input
                .getPipeline()
                .apply(
                    "Generate start timestamp",
                    new PTransform<PBegin, PCollectionView<Instant>>() {
                      @Override
                      public PCollectionView<Instant> expand(PBegin input) {
                        return input
                            .apply(Create.of("side input"))
                            .apply(
                                MapElements.into(TypeDescriptor.of(Instant.class))
                                    .via((s) -> Instant.now()))
                            .apply(View.asSingleton());
                      }
                    });
        rampupThrottlingFn = new RampupThrottlingFn<>(hintNumWorkers, startTimestampView);

        intermediateOutput =
            intermediateOutput.apply(
                "Enforce ramp-up through throttling",
                ParDo.of(rampupThrottlingFn).withSideInputs(startTimestampView));
      }
      return intermediateOutput.apply(
          "Write Mutation to Datastore",
          ParDo.of(
              new DatastoreWriterFn(
                  projectId,
                  databaseId,
                  localhost,
                  new V1DatastoreFactory(),
                  new WriteBatcherImpl())));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("projectId", projectId)
          .add("mutationFn", mutationFn.getClass().getName())
          .toString();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder
          .addIfNotNull(DisplayData.item("projectId", projectId).withLabel("Output Project"))
          .addIfNotNull(DisplayData.item("databaseId", databaseId).withLabel("Output Database"))
          .include("mutationFn", mutationFn);
      if (rampupThrottlingFn != null) {
        builder.include("rampupThrottlingFn", rampupThrottlingFn);
      }
    }

    public String getProjectId() {
      return projectId.get();
    }

    public String getDatabaseId() {
      return databaseId.get();
    }
  }

  /** Determines batch sizes for commit RPCs. */
  @VisibleForTesting
  interface WriteBatcher {

    /** Call before using this WriteBatcher. */
    void start();

    /**
     * Reports the latency of a previous commit RPC, and the number of mutations that it contained.
     */
    void addRequestLatency(long timeSinceEpochMillis, long latencyMillis, int numMutations);

    /** Returns the number of entities to include in the next CommitRequest. */
    int nextBatchSize(long timeSinceEpochMillis);
  }

  /**
   * Determines batch sizes for commit RPCs based on past performance.
   *
   * <p>It aims for a target response time per RPC: it uses the response times for previous RPCs and
   * the number of entities contained in them, calculates a rolling average time-per-entity, and
   * chooses the number of entities for future writes to hit the target time.
   *
   * <p>This enables us to send large batches without sending over-large requests in the case of
   * expensive entity writes that may timeout before the server can apply them all.
   */
  @VisibleForTesting
  static class WriteBatcherImpl implements WriteBatcher, Serializable {

    /** Target time per RPC for writes. */
    static final int DATASTORE_BATCH_TARGET_LATENCY_MS = 6000;

    @Override
    public void start() {
      meanLatencyPerEntityMs =
          new MovingAverage(
              120000 /* sample period 2 minutes */, 10000 /* sample interval 10s */,
              1 /* numSignificantBuckets */, 1 /* numSignificantSamples */);
    }

    @Override
    public void addRequestLatency(long timeSinceEpochMillis, long latencyMillis, int numMutations) {
      meanLatencyPerEntityMs.add(timeSinceEpochMillis, latencyMillis / numMutations);
    }

    @Override
    public int nextBatchSize(long timeSinceEpochMillis) {
      if (!meanLatencyPerEntityMs.hasValue(timeSinceEpochMillis)) {
        return DATASTORE_BATCH_UPDATE_ENTITIES_START;
      }
      long recentMeanLatency = Math.max(meanLatencyPerEntityMs.get(timeSinceEpochMillis), 1);
      long targetBatchSize = DATASTORE_BATCH_TARGET_LATENCY_MS / recentMeanLatency;
      return (int)
          Math.max(
              DATASTORE_BATCH_UPDATE_ENTITIES_MIN,
              Math.min(DATASTORE_BATCH_UPDATE_ENTITIES_LIMIT, targetBatchSize));
    }

    private transient MovingAverage meanLatencyPerEntityMs;
  }

  /**
   * {@link DoFn} that writes {@link Mutation}s to Cloud Datastore. Mutations are written in
   * batches; see {@link DatastoreV1.WriteBatcherImpl}.
   *
   * <p>See <a href="https://cloud.google.com/datastore/docs/concepts/entities">Datastore: Entities,
   * Properties, and Keys</a> for information about entity keys and mutations.
   *
   * <p>Commits are non-transactional. If a commit fails because of a conflict over an entity group,
   * the commit will be retried (up to {@link DatastoreV1.BaseDatastoreWriterFn#MAX_RETRIES} times).
   * This means that the mutation operation should be idempotent. Thus, the writer should only be
   * used for {@code upsert} and {@code delete} mutation operations, as these are the only two Cloud
   * Datastore mutations that are idempotent.
   */
  static class DatastoreWriterFn extends BaseDatastoreWriterFn<WriteSuccessSummary> {

    DatastoreWriterFn(String projectId, @Nullable String localhost) {
      super(projectId, localhost);
    }

    DatastoreWriterFn(ValueProvider<String> projectId, @Nullable String localhost) {
      super(projectId, localhost);
    }

    @VisibleForTesting
    DatastoreWriterFn(
        ValueProvider<String> projectId,
        @Nullable String localhost,
        V1DatastoreFactory datastoreFactory,
        WriteBatcher writeBatcher) {
      super(projectId, localhost, datastoreFactory, writeBatcher);
    }

    @VisibleForTesting
    DatastoreWriterFn(
        ValueProvider<String> projectId,
        ValueProvider<String> databaseId,
        @Nullable String localhost,
        V1DatastoreFactory datastoreFactory,
        WriteBatcher writeBatcher) {
      super(projectId, databaseId, localhost, datastoreFactory, writeBatcher);
    }

    @Override
    void handleWriteSummary(
        ContextAdapter<WriteSuccessSummary> context,
        Instant timestamp,
        KV<WriteSuccessSummary, BoundedWindow> tuple,
        Runnable logMessage) {
      logMessage.run();
      context.output(tuple.getKey(), timestamp, tuple.getValue());
    }
  }

  abstract static class BaseDatastoreWriterFn<OutT> extends DoFn<Mutation, OutT> {

    private static final Logger LOG = LoggerFactory.getLogger(BaseDatastoreWriterFn.class);
    private final ValueProvider<String> projectId;
    private final ValueProvider<String> databaseId;
    private final @Nullable String localhost;
    private transient Datastore datastore;
    private final V1DatastoreFactory datastoreFactory;
    // Current batch of mutations to be written.
    private final List<KV<Mutation, BoundedWindow>> mutations = new ArrayList<>();
    private final HashSet<com.google.datastore.v1.Key> uniqueMutationKeys = new HashSet<>();
    private int mutationsSize = 0; // Accumulated size of protos in mutations.
    private WriteBatcher writeBatcher;

    private transient AdaptiveThrottler adaptiveThrottler;
    private final Counter throttlingMsecs =
        Metrics.counter(DatastoreWriterFn.class, Metrics.THROTTLE_TIME_COUNTER_NAME);
    private final Counter rpcErrors =
        Metrics.counter(DatastoreWriterFn.class, "datastoreRpcErrors");
    private final Counter rpcSuccesses =
        Metrics.counter(DatastoreWriterFn.class, "datastoreRpcSuccesses");
    private final Distribution batchSize =
        Metrics.distribution(DatastoreWriterFn.class, "batchSize");
    private final Counter entitiesMutated =
        Metrics.counter(DatastoreWriterFn.class, "datastoreEntitiesMutated");
    private final Distribution latencyMsPerMutation =
        Metrics.distribution(DatastoreWriterFn.class, "datastoreLatencyMsPerMutation");

    private static final int MAX_RETRIES = 5;
    private static final FluentBackoff BUNDLE_WRITE_BACKOFF =
        FluentBackoff.DEFAULT
            .withMaxRetries(MAX_RETRIES)
            .withInitialBackoff(Duration.standardSeconds(5));

    BaseDatastoreWriterFn(String projectId, @Nullable String localhost) {
      this(
          StaticValueProvider.of(projectId),
          null,
          localhost,
          new V1DatastoreFactory(),
          new WriteBatcherImpl());
    }

    BaseDatastoreWriterFn(ValueProvider<String> projectId, @Nullable String localhost) {
      this(projectId, null, localhost, new V1DatastoreFactory(), new WriteBatcherImpl());
    }

    BaseDatastoreWriterFn(
        ValueProvider<String> projectId,
        @Nullable String localhost,
        V1DatastoreFactory datastoreFactory,
        WriteBatcher writeBatcher) {
      this(projectId, null, localhost, datastoreFactory, writeBatcher);
    }

    BaseDatastoreWriterFn(
        ValueProvider<String> projectId,
        ValueProvider<String> databaseId,
        @Nullable String localhost,
        V1DatastoreFactory datastoreFactory,
        WriteBatcher writeBatcher) {
      this.projectId = checkNotNull(projectId, "projectId");
      this.databaseId = databaseId;
      this.localhost = localhost;
      this.datastoreFactory = datastoreFactory;
      this.writeBatcher = writeBatcher;
    }

    /**
     * Adapter interface which provides a common parent for {@link ProcessContext} and {@link
     * FinishBundleContext} so that we are able to use a single common invocation to output from.
     */
    interface ContextAdapter<T> {
      void output(T t, Instant timestamp, BoundedWindow window);
    }

    private static final class ProcessContextAdapter<T>
        implements DatastoreV1.BaseDatastoreWriterFn.ContextAdapter<T> {
      private final DoFn<Mutation, T>.ProcessContext context;

      private ProcessContextAdapter(DoFn<Mutation, T>.ProcessContext context) {
        this.context = context;
      }

      @Override
      public void output(T t, Instant timestamp, BoundedWindow window) {
        context.outputWithTimestamp(t, timestamp);
      }
    }

    private static final class FinishBundleContextAdapter<T>
        implements DatastoreV1.BaseDatastoreWriterFn.ContextAdapter<T> {
      private final DoFn<Mutation, T>.FinishBundleContext context;

      private FinishBundleContextAdapter(DoFn<Mutation, T>.FinishBundleContext context) {
        this.context = context;
      }

      @Override
      public void output(T t, Instant timestamp, BoundedWindow window) {
        context.output(t, timestamp, window);
      }
    }

    abstract void handleWriteSummary(
        ContextAdapter<OutT> context,
        Instant timestamp,
        KV<WriteSuccessSummary, BoundedWindow> tuple,
        Runnable logMessage);

    @StartBundle
    public void startBundle(StartBundleContext c) {
      String databaseIdOrDefaultDatabase = databaseId == null ? DEFAULT_DATABASE : databaseId.get();
      datastore =
          datastoreFactory.getDatastore(
              c.getPipelineOptions(), projectId.get(), databaseIdOrDefaultDatabase, localhost);
      writeBatcher.start();
      if (adaptiveThrottler == null) {
        // Initialize throttler at first use, because it is not serializable.
        adaptiveThrottler = new AdaptiveThrottler(120000, 10000, 1.25);
      }
    }

    private static com.google.datastore.v1.Key getKey(Mutation m) {
      if (m.hasUpsert()) {
        return m.getUpsert().getKey();
      } else if (m.hasInsert()) {
        return m.getInsert().getKey();
      } else if (m.hasDelete()) {
        return m.getDelete();
      } else if (m.hasUpdate()) {
        return m.getUpdate().getKey();
      } else {
        LOG.warn("Mutation {} does not have an operation type set.", m);
        return Entity.getDefaultInstance().getKey();
      }
    }

    @ProcessElement
    public void processElement(ProcessContext c, BoundedWindow window) throws Exception {
      Mutation mutation = c.element();
      int size = mutation.getSerializedSize();
      ProcessContextAdapter<OutT> contextAdapter = new ProcessContextAdapter<>(c);

      if (!uniqueMutationKeys.add(getKey(mutation))) {
        flushBatch(contextAdapter);
      }

      if (mutations.size() > 0
          && mutationsSize + size >= DatastoreV1.DATASTORE_BATCH_UPDATE_BYTES_LIMIT) {
        flushBatch(contextAdapter);
      }
      mutations.add(KV.of(c.element(), window));
      mutationsSize += size;
      if (mutations.size() >= writeBatcher.nextBatchSize(System.currentTimeMillis())) {
        flushBatch(contextAdapter);
      }
    }

    @FinishBundle
    public void finishBundle(FinishBundleContext c) throws Exception {
      if (!mutations.isEmpty()) {
        flushBatch(new FinishBundleContextAdapter<>(c));
      }
    }

    /**
     * Writes a batch of mutations to Cloud Datastore.
     *
     * <p>If a commit fails, it will be retried up to {@link #MAX_RETRIES} times. All mutations in
     * the batch will be committed again, even if the commit was partially successful. If the retry
     * limit is exceeded, the last exception from Cloud Datastore will be thrown.
     *
     * @throws DatastoreException if the commit fails or IOException or InterruptedException if
     *     backing off between retries fails.
     */
    private synchronized void flushBatch(ContextAdapter<OutT> context)
        throws DatastoreException, IOException, InterruptedException {

      LOG.debug("Writing batch of {} mutations", mutations.size());
      Sleeper sleeper = Sleeper.DEFAULT;
      BackOff backoff = BUNDLE_WRITE_BACKOFF.backoff();

      batchSize.update(mutations.size());

      String databaseIdOrDefaultDatabase = databaseId == null ? DEFAULT_DATABASE : databaseId.get();
      CommitResponse response;
      BoundedWindow okWindow;
      Instant end;

      while (true) {
        // Batch upsert entities.
        CommitRequest.Builder commitRequest = CommitRequest.newBuilder();
        commitRequest.addAllMutations(
            mutations.stream().map(KV::getKey).collect(Collectors.toList()));
        commitRequest.setMode(CommitRequest.Mode.NON_TRANSACTIONAL);
        commitRequest.setProjectId(projectId.get());
        commitRequest.setDatabaseId(databaseIdOrDefaultDatabase);
        long startTime = System.currentTimeMillis(), endTime;

        if (adaptiveThrottler.throttleRequest(startTime)) {
          LOG.info("Delaying request due to previous failures");
          throttlingMsecs.inc(WriteBatcherImpl.DATASTORE_BATCH_TARGET_LATENCY_MS);
          sleeper.sleep(WriteBatcherImpl.DATASTORE_BATCH_TARGET_LATENCY_MS);
          continue;
        }

        HashMap<String, String> baseLabels = new HashMap<>();
        baseLabels.put(MonitoringInfoConstants.Labels.PTRANSFORM, "");
        baseLabels.put(MonitoringInfoConstants.Labels.SERVICE, "Datastore");
        baseLabels.put(MonitoringInfoConstants.Labels.METHOD, "BatchDatastoreWrite");
        baseLabels.put(
            MonitoringInfoConstants.Labels.RESOURCE,
            GcpResourceIdentifiers.datastoreResource(projectId.get(), ""));
        baseLabels.put(MonitoringInfoConstants.Labels.DATASTORE_PROJECT, projectId.get());
        baseLabels.put(MonitoringInfoConstants.Labels.DATASTORE_NAMESPACE, "");
        ServiceCallMetric serviceCallMetric =
            new ServiceCallMetric(MonitoringInfoConstants.Urns.API_REQUEST_COUNT, baseLabels);
        try {

          response = datastore.commit(commitRequest.build());
          endTime = System.currentTimeMillis();
          end = Instant.ofEpochMilli(endTime);
          okWindow = Iterables.getLast(mutations).getValue();
          serviceCallMetric.call("ok");

          writeBatcher.addRequestLatency(endTime, endTime - startTime, mutations.size());
          adaptiveThrottler.successfulRequest(startTime);
          latencyMsPerMutation.update((endTime - startTime) / mutations.size());
          rpcSuccesses.inc();
          entitiesMutated.inc(mutations.size());
          // Break if the commit threw no exception.
          break;
        } catch (DatastoreException exception) {
          serviceCallMetric.call(exception.getCode().getNumber());
          if (exception.getCode() == Code.DEADLINE_EXCEEDED) {
            /* Most errors are not related to request size, and should not change our expectation of
             * the latency of successful requests. DEADLINE_EXCEEDED can be taken into
             * consideration, though. */
            endTime = System.currentTimeMillis();
            writeBatcher.addRequestLatency(endTime, endTime - startTime, mutations.size());
            latencyMsPerMutation.update((endTime - startTime) / mutations.size());
          }
          // Only log the code and message for potentially-transient errors. The entire exception
          // will be propagated upon the last retry.
          LOG.error(
              "Error writing batch of {} mutations to Datastore ({}): {}",
              mutations.size(),
              exception.getCode(),
              exception.getMessage());
          rpcErrors.inc();

          if (NON_RETRYABLE_ERRORS.contains(exception.getCode())) {
            throw exception;
          }
          if (!BackOffUtils.next(sleeper, backoff)) {
            LOG.error("Aborting after {} retries.", MAX_RETRIES);
            throw exception;
          }
        }
      }
      int okCount = mutations.size();
      long okBytes = response.getSerializedSize();
      handleWriteSummary(
          context,
          end,
          KV.of(new WriteSuccessSummary(okCount, okBytes), okWindow),
          () -> LOG.debug("Successfully wrote {} mutations", mutations.size()));

      mutations.clear();
      uniqueMutationKeys.clear();
      mutationsSize = 0;
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      super.populateDisplayData(builder);
      builder.addIfNotNull(DisplayData.item("projectId", projectId).withLabel("Output Project"));
    }
  }

  /**
   * Returns true if a Cloud Datastore key is complete. A key is complete if its last element has
   * either an id or a name.
   */
  static boolean isValidKey(Key key) {
    List<PathElement> elementList = key.getPathList();
    if (elementList.isEmpty()) {
      return false;
    }
    PathElement lastElement = elementList.get(elementList.size() - 1);
    return (lastElement.getId() != 0 || !lastElement.getName().isEmpty());
  }

  /** A function that constructs an upsert {@link Mutation} from an {@link Entity}. */
  @VisibleForTesting
  static class UpsertFn extends SimpleFunction<Entity, Mutation> {
    @Override
    public Mutation apply(Entity entity) {
      // Verify that the entity to write has a complete key.
      checkArgument(
          isValidKey(entity.getKey()),
          "Entities to be written to the Cloud Datastore must have complete keys:\n%s",
          entity);

      return makeUpsert(entity).build();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.add(
          DisplayData.item("upsertFn", this.getClass()).withLabel("Create Upsert Mutation"));
    }
  }

  /** A function that constructs a delete {@link Mutation} from an {@link Entity}. */
  @VisibleForTesting
  static class DeleteEntityFn extends SimpleFunction<Entity, Mutation> {
    @Override
    public Mutation apply(Entity entity) {
      // Verify that the entity to delete has a complete key.
      checkArgument(
          isValidKey(entity.getKey()),
          "Entities to be deleted from the Cloud Datastore must have complete keys:\n%s",
          entity);

      return makeDelete(entity.getKey()).build();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.add(
          DisplayData.item("deleteEntityFn", this.getClass()).withLabel("Create Delete Mutation"));
    }
  }

  /** A function that constructs a delete {@link Mutation} from a {@link Key}. */
  @VisibleForTesting
  static class DeleteKeyFn extends SimpleFunction<Key, Mutation> {
    @Override
    public Mutation apply(Key key) {
      // Verify that the entity to delete has a complete key.
      checkArgument(
          isValidKey(key),
          "Keys to be deleted from the Cloud Datastore must be complete:\n%s",
          key);

      return makeDelete(key).build();
    }

    @Override
    public void populateDisplayData(DisplayData.Builder builder) {
      builder.add(
          DisplayData.item("deleteKeyFn", this.getClass()).withLabel("Create Delete Mutation"));
    }
  }

  /**
   * A wrapper factory class for Cloud Datastore singleton classes {@link DatastoreFactory} and
   * {@link QuerySplitter}
   *
   * <p>{@link DatastoreFactory} and {@link QuerySplitter} are not java serializable, hence wrapping
   * them under this class, which implements {@link Serializable}.
   */
  @VisibleForTesting
  static class V1DatastoreFactory implements Serializable {

    /** Builds a Cloud Datastore client for the given pipeline options and project. */
    public Datastore getDatastore(
        PipelineOptions pipelineOptions, String projectId, String databaseId) {
      return getDatastore(pipelineOptions, projectId, databaseId, null);
    }

    /**
     * Builds a Cloud Datastore client for the given pipeline options, project and an optional
     * locahost.
     */
    public Datastore getDatastore(
        PipelineOptions pipelineOptions,
        String projectId,
        String databaseId,
        @Nullable String localhost) {
      Credentials credential = pipelineOptions.as(GcpOptions.class).getGcpCredential();

      // Add Beam version to user agent header.
      HttpRequestInitializer userAgentInitializer =
          request -> request.getHeaders().setUserAgent(pipelineOptions.getUserAgent());
      HttpRequestInitializer initializer;
      if (credential != null) {
        initializer =
            new ChainingHttpRequestInitializer(
                new HttpCredentialsAdapter(credential),
                new RetryHttpRequestInitializer(),
                userAgentInitializer);
      } else {
        initializer =
            new ChainingHttpRequestInitializer(
                new RetryHttpRequestInitializer(), userAgentInitializer);
      }

      DatastoreOptions.Builder builder =
          new DatastoreOptions.Builder().projectId(projectId).initializer(initializer);

      if (localhost != null) {
        builder.localHost(localhost);
      } else {
        builder.host("batch-datastore.googleapis.com");
      }

      return DatastoreFactory.get().create(builder.build());
    }

    /** Builds a Cloud Datastore {@link QuerySplitter}. */
    public QuerySplitter getQuerySplitter() {
      return DatastoreHelper.getQuerySplitter();
    }
  }
}
