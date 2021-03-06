/*
 * Copyright (c) 2014-2015 Globo.com - ATeam
 * All rights reserved.
 *
 * This source is subject to the Apache License, Version 2.0.
 * Please see the LICENSE file for more information.
 *
 * Authors: See AUTHORS file
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.galeb.services.healthchecker.sched;

import static io.galeb.core.model.BackendPool.PROP_HEALTHCHECK_CODE;
import static io.galeb.core.model.BackendPool.PROP_HEALTHCHECK_HOST;
import static io.galeb.core.model.BackendPool.PROP_HEALTHCHECK_PATH;
import static io.galeb.core.model.BackendPool.PROP_HEALTHCHECK_RETURN;
import static io.galeb.services.healthchecker.HealthChecker.PROP_HEALTHCHECKER_FOLLOW_REDIR;
import static io.galeb.services.healthchecker.HealthChecker.PROP_HEALTHCHECKER_INTERVAL;
import static io.galeb.services.healthchecker.HealthChecker.PROP_HEALTHCHECKER_THREADS;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import io.galeb.core.cluster.ignite.IgniteCacheFactory;
import io.galeb.core.jcache.CacheFactory;
import io.galeb.core.json.JsonObject;
import io.galeb.core.model.Farm;
import io.galeb.core.services.AbstractService;
import io.galeb.services.healthchecker.testers.RestAssuredTester;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import io.galeb.core.model.Backend;
import io.galeb.core.model.BackendPool;
import io.galeb.core.model.Entity;
import io.galeb.core.model.Rule;
import io.galeb.services.healthchecker.HealthChecker;

import javax.cache.Cache;

@DisallowConcurrentExecution
public class HealthCheckJob implements Job {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final int CONN_TIMEOUT_DELTA = 1000;
    private static Integer threads = Integer.parseInt(System.getProperty(PROP_HEALTHCHECKER_THREADS,
            String.valueOf(Runtime.getRuntime().availableProcessors())));

    private CacheFactory cacheFactory = IgniteCacheFactory.getInstance();

    private final ExecutorService executor = Executors.newWorkStealingPool(threads);
    private Map<String, Future> futureMap = null;
    private Farm farm = null;

    @SuppressWarnings("unchecked")
    private void init(final JobDataMap jobDataMap) {
        if (futureMap == null) {
            futureMap = (Map<String, Future>) jobDataMap.get(HealthChecker.FUTURE_MAP);
        }
        if (farm == null) {
            farm = (Farm) jobDataMap.get(AbstractService.FARM_KEY);
        }
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        init(context.getJobDetail().getJobDataMap());

        LOGGER.info("=== " + this.getClass().getSimpleName() + " ===");

        Cache<String, String> pools = cacheFactory.getCache(BackendPool.class.getName());
        Cache<String, String> backends = cacheFactory.getCache(Backend.class.getName());
        Stream<Cache.Entry<String, String>> streamOfBackendPools = StreamSupport.stream(pools.spliterator(), true);

        streamOfBackendPools.parallel().forEach(entry -> {
            final BackendPool backendPool = (BackendPool) JsonObject.fromJson(entry.getValue(), BackendPool.class);
            final String backendPoolId = backendPool.getId();
            final Stream<Backend> streamOfBackends = StreamSupport.stream(backends.spliterator(), false)
                    .map(entry2 -> (Backend) JsonObject.fromJson(entry2.getValue(), Backend.class))
                    .filter(b -> b.getParentId().equals(backendPoolId));
            checkBackendPool(streamOfBackends, getProperties(backendPool));
        });

        LOGGER.debug("Job HealthCheck done.");

    }

    private Map<String, Object> getProperties(final Entity pool) {
        final Map<String, Object> properties = new HashMap<>(pool.getProperties());
        final String hcBody = Optional.ofNullable((String) properties.get(PROP_HEALTHCHECK_RETURN))
                                        .orElse(System.getProperty(HealthChecker.PROP_HEALTHCHECKER_DEF_BODY, ""));
        final String hcPath = Optional.ofNullable((String) properties.get(PROP_HEALTHCHECK_PATH))
                                        .orElse(System.getProperty(HealthChecker.PROP_HEALTHCHECKER_DEF_PATH, "/"));
        final String hcHost = Optional.ofNullable((String) properties.get(PROP_HEALTHCHECK_HOST))
                                        .orElse(getHost(pool));
        final String hcStatusCode = Optional.ofNullable((String) properties.get(PROP_HEALTHCHECK_CODE))
                                        .orElse("0");
        properties.put(PROP_HEALTHCHECK_RETURN, hcBody);
        properties.put(PROP_HEALTHCHECK_PATH, hcPath);
        properties.put(PROP_HEALTHCHECK_HOST, hcHost);
        try {
            properties.put(PROP_HEALTHCHECK_CODE, Integer.parseInt(hcStatusCode));
        } catch (Exception e) {
            properties.put(PROP_HEALTHCHECK_CODE, 0);
        }
        return Collections.unmodifiableMap(properties);
    }

    private void checkBackendPool(final Stream<Backend> streamOfBackends, final Map<String, Object> properties) {

        final String hcBody = (String) properties.get(PROP_HEALTHCHECK_RETURN);
        final String hcPath = (String) properties.get(PROP_HEALTHCHECK_PATH);
        final String hcHost = (String) properties.get(PROP_HEALTHCHECK_HOST);
        final int statusCode = (int) properties.get(PROP_HEALTHCHECK_CODE);

        streamOfBackends.forEach(backend ->
        {
            String connTimeOut = System.getProperty(PROP_HEALTHCHECKER_INTERVAL, "10000");
            String followRedirects = System.getProperty(PROP_HEALTHCHECKER_FOLLOW_REDIR);

            if (backend != null) {
                final String hostWithPort = backend.getId();
                final String fullPath = hostWithPort+hcPath;
                try {
                    final String futureKey = backend.compoundId();
                    Future future = futureMap.get(futureKey);
                    if (future == null || future.isDone() || future.isCancelled()) {
                        LOGGER.debug("Processing " + futureKey);
                        future = executor.submit(() -> new RestAssuredTester()
                                .reset()
                                .withUrl(fullPath)
                                .withHost(hcHost)
                                .withStatusCode(statusCode)
                                .withBody(hcBody)
                                .setConnectionTimeOut(connTimeOut != null ?
                                        Integer.parseInt(connTimeOut) - CONN_TIMEOUT_DELTA : null)
                                .followRedirects(followRedirects != null ?
                                        Boolean.parseBoolean(followRedirects) : null)
                                .setEntity(backend)
                                .setCache(cacheFactory.getCache(Backend.class.getName()))
                                .check());
                        futureMap.put(futureKey, future);
                    }
                } catch (Exception e) {
                    LOGGER.error(hostWithPort+": "+e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    private String getHost(Entity pool) {
        Cache<String, String> rules = cacheFactory.getCache(Rule.class.getName());
        Stream<Cache.Entry<String, String>> streamOfRules = StreamSupport.stream(rules.spliterator(), false);

        Optional<Rule> rule = streamOfRules.map(entry -> (Rule) JsonObject.fromJson(entry.getValue(), Rule.class))
                .filter(r -> pool.getId().equalsIgnoreCase((String) r.getProperty(Rule.PROP_TARGET_ID)))
                .findAny();

        return rule.isPresent() ? rule.get().getParentId() : "";
    }

}
