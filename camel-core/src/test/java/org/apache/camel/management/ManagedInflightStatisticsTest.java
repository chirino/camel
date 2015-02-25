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
package org.apache.camel.management;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.util.Date;
import java.util.Set;

/**
 * @version 
 */
public class ManagedInflightStatisticsTest extends ManagementTestSupport {

    public void testManageStatistics() throws Exception {
        // JMX tests dont work well on AIX CI servers (hangs them)
        if (isPlatform("aix")) {
            return;
        }

        // get the stats for the route
        MBeanServer mbeanServer = getMBeanServer();

        Set<ObjectName> set = mbeanServer.queryNames(new ObjectName("*:type=routes,*"), null);
        assertEquals(1, set.size());
        ObjectName on = set.iterator().next();

        Long inflight = (Long) mbeanServer.getAttribute(on, "ExchangesInflight");
        assertEquals(0, inflight.longValue());
        Long ts = (Long) mbeanServer.getAttribute(on, "OldestInflightDuration");
        assertNull(ts);
        String id = (String) mbeanServer.getAttribute(on, "OldestInflightExchangeId");
        assertNull(id);

        MockEndpoint result = getMockEndpoint("mock:result");
        result.expectedMessageCount(2);

        // start some exchanges.
        template.asyncSendBody("direct:start", 1000L);
        Thread.sleep(500);
        template.asyncSendBody("direct:start", 1000L);
        Thread.sleep(100);

        inflight = (Long) mbeanServer.getAttribute(on, "ExchangesInflight");
        assertEquals(2, inflight.longValue());

        ts = (Long) mbeanServer.getAttribute(on, "OldestInflightDuration");
        assertNotNull(ts);
        id = (String) mbeanServer.getAttribute(on, "OldestInflightExchangeId");
        assertNotNull(id);

        // Lets wait for the first exchange to complete.
        Thread.sleep(500);
        Long ts2 = (Long) mbeanServer.getAttribute(on, "OldestInflightDuration");
        assertNotNull(ts2);
        String id2 = (String) mbeanServer.getAttribute(on, "OldestInflightExchangeId");
        assertNotNull(id2);

        // Lets verify the oldest changed.
        assertTrue(!id2.equals(id));
        assertTrue(ts2 > ts);

        // Lets wait for all the exchanges to complete.
        Thread.sleep(500);

        assertMockEndpointsSatisfied();

        inflight = (Long) mbeanServer.getAttribute(on, "ExchangesInflight");
        assertEquals(0, inflight.longValue());
        ts = (Long) mbeanServer.getAttribute(on, "OldestInflightDuration");
        assertNull(ts);
        id = (String) mbeanServer.getAttribute(on, "OldestInflightExchangeId");
        assertNull(id);

    }

    @Override
    protected RouteBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start")
                    .process(new Processor() {
                        @Override
                        public void process(Exchange exchange) throws Exception {
                            Long delay = (Long) exchange.getIn().getBody();
//                            System.out.println("Sleeping: " + delay);
                            Thread.sleep(delay.longValue());
//                            System.out.println("Done Sleeping: " + delay);
                        }
                    })
                    .to("mock:result").id("mock");;
            }
        };
    }

}