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

import io.galeb.core.cluster.DistributedMap;
import io.galeb.core.logging.Logger;
import io.galeb.core.model.Backend;
import io.galeb.core.model.Backend.Health;
import io.galeb.core.model.BackendPool;
import io.galeb.core.model.Entity;
import io.galeb.core.model.Farm;
import io.galeb.core.model.Rule;
import io.galeb.core.model.collections.BackendPoolCollection;
import io.galeb.core.sched.QuartzScheduler;
import io.galeb.services.healthchecker.HealthChecker;
import io.galeb.services.healthchecker.Tester;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@DisallowConcurrentExecution
public class HealthCheckJob implements Job {

    private Tester tester;
    private Logger logger;
    private Farm farm;
    private DistributedMap<String, Entity> distributedMap;

    @SuppressWarnings("unchecked")
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {

        final JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();

        if (logger==null) {
            logger = (Logger) jobDataMap.get(QuartzScheduler.LOGGER);
        }
        if (tester==null) {
            tester = (Tester) jobDataMap.get(HealthChecker.TESTER_NAME);
            tester.setLogger(logger);
        }
        if (farm==null) {
            farm = (Farm) jobDataMap.get(QuartzScheduler.FARM);
        }
        if (distributedMap==null) {
            distributedMap = (DistributedMap<String, Entity>) jobDataMap.get(QuartzScheduler.DISTRIBUTEDMAP);
        }

        final Set<Entity> backends = farm.getCollection(Backend.class);
        for (final Entity backend : backends) {
            final BackendPoolCollection backendPoolCollection = (BackendPoolCollection) farm.getCollection(BackendPool.class);
            final List<Entity> backendPools = backendPoolCollection.getListByID(backend.getParentId());
            if (!backendPools.isEmpty()) {
                final BackendPool backendPool = (BackendPool) backendPools.get(0);
                final String url = backend.getId();
                String returnType = "string";
                final Backend.Health lastHealth = ((Backend) backend).getHealth();

                String host = (String) backendPool.getProperty(BackendPool.PROP_HEALTHCHECK_HOST);
                if (host==null) {
                    Optional<Entity> rule = farm.getCollection(Rule.class).stream()
                            .filter(r -> backendPool.getId().equalsIgnoreCase((String) r.getProperty(Rule.PROP_TARGET_ID)))
                            .findAny();
                    host = rule.isPresent() ? rule.get().getParentId() : url;
                }

                String healthCheckPath = (String) backendPool.getProperty(BackendPool.PROP_HEALTHCHECK_PATH);
                if (healthCheckPath==null) {
                    healthCheckPath = "/";
                }

                final String fullUrl = url + healthCheckPath;

                String expectedReturn = (String) backendPool.getProperty(BackendPool.PROP_HEALTHCHECK_RETURN);
                if (expectedReturn==null) {
                    returnType = "httpCode200";
                    expectedReturn="OK";
                }

                boolean isOk = false;
                try {
                    isOk = tester.withUrl(fullUrl)
                                 .withHost(host)
                                 .withHealthCheckPath(healthCheckPath)
                                 .withReturn(returnType, expectedReturn)
                                 .connect();

                } catch (RuntimeException | InterruptedException | ExecutionException e) {
                    logger.debug(e);
                }

                if (isOk) {
                    ((Backend) backend).setHealth(Health.HEALTHY);
                    loggerDebug(url+" is OK");
                } else {
                    ((Backend) backend).setHealth(Health.DEADY);
                    loggerDebug(url+" FAIL");
                }
                if (((Backend) backend).getHealth()!=lastHealth) {
                    distributedMap.getMap(Backend.class.getName()).put(backend.getId(), backend);
                }
            }
        }

        loggerDebug("Job HealthCheck done.");

    }

    private void loggerDebug(String message) {
        if (logger!=null) {
            logger.debug(message);
        }
    }

}
