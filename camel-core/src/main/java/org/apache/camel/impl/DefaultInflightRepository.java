/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.MessageHistory;
import org.apache.camel.spi.InflightRepository;
import org.apache.camel.support.ServiceSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link org.apache.camel.spi.InflightRepository}.
 *
 * @version 
 */
public class DefaultInflightRepository extends ServiceSupport implements InflightRepository {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultInflightRepository.class);
    private final ConcurrentMap<String, Exchange> inflight = new ConcurrentHashMap<String, Exchange>();
    private final ConcurrentMap<String, AtomicInteger> routeCount = new ConcurrentHashMap<String, AtomicInteger>();

    public void add(Exchange exchange) {
        inflight.put(exchange.getExchangeId(), exchange);
    }

    public void remove(Exchange exchange) {
        inflight.remove(exchange.getExchangeId());
    }

    public void add(Exchange exchange, String routeId) {
        AtomicInteger existing = routeCount.putIfAbsent(routeId, new AtomicInteger(1));
        if (existing != null) {
            existing.incrementAndGet();
        }
    }

    public void remove(Exchange exchange, String routeId) {
        AtomicInteger existing = routeCount.get(routeId);
        if (existing != null) {
            existing.decrementAndGet();
        }
    }

    public int size() {
        return inflight.size();
    }

    @Deprecated
    public int size(Endpoint endpoint) {
        return 0;
    }

    @Override
    public void removeRoute(String routeId) {
        routeCount.remove(routeId);
    }

    @Override
    public int size(String routeId) {
        AtomicInteger existing = routeCount.get(routeId);
        return existing != null ? existing.get() : 0;
    }

    @Override
    public Collection<InflightExchange> browse() {
        return browse(-1, false);
    }

    @Override
    public Collection<InflightExchange> browse( int limit, boolean sortByLongestDuration) {
        Comparator<InflightExchange> comparator = sortByLongestDuration ?
            new Comparator<InflightExchange>() {
                @Override
                public int compare(InflightExchange e1, InflightExchange e2) {
                    long d1 = e1.getDuration();
                    long d2 = e2.getDuration();
                    return Long.compare(d1, d2);
                }
            }
        :
            new Comparator<InflightExchange>() {
                @Override
                public int compare(InflightExchange e1, InflightExchange e2) {
                    return e1.getExchange().getExchangeId().compareTo(e2.getExchange().getExchangeId());
                }
            }
        ;
        return browse(null, limit, comparator);
    }

    @Override
    public Collection<InflightExchange> browse(String routeId, int limit, Comparator<InflightExchange> comparator) {

        ArrayList<InflightExchange> matches = new ArrayList<InflightExchange>();
        for (Exchange exchange : inflight.values()) {
            InflightExchangeEntry entry = new InflightExchangeEntry(exchange);
            if( routeId==null || routeId.equals(entry.getRouteId()) ) {
                matches.add(entry);
            }
        }
        
        if (comparator!=null) {
            Collections.sort(matches, comparator);
        }

        if( limit <= 0 || matches.size() <= limit  ) {
            return Collections.unmodifiableCollection(matches);
        } else {
            ArrayList<InflightExchange> limited = new ArrayList<InflightExchange>(limit);
            for (InflightExchange entry : matches) {
                limited.add(entry);
                if (limit > 0 && limited.size() >= limit) {
                    break;
                }
            }
            return Collections.unmodifiableCollection(limited);
        }
    }

    @Override
    protected void doStart() throws Exception {
    }

    @Override
    protected void doStop() throws Exception {
        int count = size();
        if (count > 0) {
            LOG.warn("Shutting down while there are still " + count + " inflight exchanges.");
        } else {
            LOG.debug("Shutting down with no inflight exchanges.");
        }
        routeCount.clear();
    }

    private static long getExchangeDuration(Exchange exchange) {
        long duration = 0;
        Date created = exchange.getProperty(Exchange.CREATED_TIMESTAMP, Date.class);
        if (created != null) {
            duration = System.currentTimeMillis() - created.getTime();
        }
        return duration;
    }

    private static final class InflightExchangeEntry implements InflightExchange {

        private final Exchange exchange;

        private InflightExchangeEntry(Exchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public Exchange getExchange() {
            return exchange;
        }

        @Override
        public long getDuration() {
            return DefaultInflightRepository.getExchangeDuration(exchange);
        }

        @Override
        @SuppressWarnings("unchecked")
        public long getElapsed() {
            List<MessageHistory> list = exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
            if (list == null || list.isEmpty()) {
                return 0;
            }

            // get latest entry
            MessageHistory history = list.get(list.size() - 1);
            if (history != null) {
                return history.getElapsed();
            } else {
                return 0;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public String getNodeId() {
            List<MessageHistory> list = exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
            if (list == null || list.isEmpty()) {
                return null;
            }

            // get latest entry
            MessageHistory history = list.get(list.size() - 1);
            if (history != null) {
                return history.getNode().getId();
            } else {
                return null;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public String getRouteId() {
            List<MessageHistory> list = exchange.getProperty(Exchange.MESSAGE_HISTORY, List.class);
            if (list == null || list.isEmpty()) {
                return null;
            }

            // get latest entry
            MessageHistory history = list.get(list.size() - 1);
            if (history != null) {
                return history.getRouteId();
            } else {
                return null;
            }
        }

        @Override
        public String toString() {
            return "InflightExchangeEntry[exchangeId=" + exchange.getExchangeId() + "]";
        }
    }

}
