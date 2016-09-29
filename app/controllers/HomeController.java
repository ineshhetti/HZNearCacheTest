package controllers;


import com.hazelcast.config.*;
import com.hazelcast.core.Client;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.instance.HazelcastInstanceImpl;
import com.hazelcast.instance.HazelcastInstanceProxy;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.nearcache.NearCacheProvider;
import com.hazelcast.spi.impl.NodeEngineImpl;
import play.Logger;
import play.mvc.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This controller contains an action to handle HTTP requests
 * to the application's home page.
 */
public class HomeController extends Controller {

    /**
     * An action that renders an HTML page with a welcome message.
     * The configuration in the <code>routes</code> file means that
     * this method will be called when the application receives a
     * <code>GET</code> request with a path of <code>/</code>.
     */
    
    private static final String mapName = "HZNearCacheTesttestMap";

    private static final int numGetters = 7;

    private static final int maxRuntime = 30;

    private static final String key = "key123";

    private ConcurrentMap<String, Integer> map;

    private AtomicInteger valuePut = new AtomicInteger(0);

    private AtomicBoolean stop = new AtomicBoolean(false);

    private AtomicInteger assertionViolationCount = new AtomicInteger(0);
    
    private AtomicInteger assertionRecoveryCount = new AtomicInteger(0);

    private AtomicBoolean failed = new AtomicBoolean(false);
    
    private int waitforit = 1000;

    private StringBuilder res = new StringBuilder();
    
    public Result index(){
        return ok("hhhhhhhhhhhhhh");
    }
    public Result indexStrict(boolean strict, int waitforit) throws Exception {
        assertionViolationCount.set(0);
        assertionRecoveryCount.set(0);
        res = new StringBuilder();
        this.waitforit = waitforit;
        indexRun(strict);
        return ok(res.toString());
    }

    private void Log(String message){
        Logger.info(message);
        res.append(message);
        res.append("\n");
    }
    public void indexRun(boolean strict) throws Exception {
        // create hazelcast config
        Config config = new XmlConfigBuilder().build();
        config.setProperty("hazelcast.logging.type", "log4j");

        // we use a single node only - do not search for others
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);

        
        Log(Thread.currentThread().getName() + " info.");
        Log(Thread.currentThread().getName() + " warn.");
        NearCacheConfig nearCacheConfig = new NearCacheConfig();
        nearCacheConfig.setCacheLocalEntries(true); // this enables caching of local entries
        nearCacheConfig.setInMemoryFormat(InMemoryFormat.OBJECT);

        // create Hazelcast instance
        HazelcastInstance hcInstance = Hazelcast.newHazelcastInstance(config);

        for (Client client : hcInstance.getClientService().getConnectedClients()) {
            System.out.println(client.getSocketAddress().toString());
        }
        // enable near cache
        MapConfig mapConfig = hcInstance.getConfig().getMapConfig(mapName);
        mapConfig.setNearCacheConfig(nearCacheConfig);

        map = hcInstance.getMap(mapName);
        // run test
        runTestInternal();

        // test eventually consistent
        Thread.sleep(2000);
        int valuePutLast = valuePut.get();
        Integer valueMap = map.get(key);

        // fail if not eventually consistent
        String msg = null;
        if (valueMap < valuePutLast) {
            msg = "Near cache did *not* become consistent. (valueMap = " + valueMap + ", valuePut = " + valuePutLast + ").";

            // flush near cache and re-fetch value
            flushNearCache(hcInstance);
            Integer valueMap2 = map.get(key);

            // test again
            if (valueMap2 < valuePutLast) {
                msg += " Unexpected inconsistency! (valueMap2 = " + valueMap2 + ", valuePut = " + valuePutLast + ").";
            } else {
                msg += " Flushing the near cache cleared the inconsistency. (valueMap2 = " + valueMap2 + ", valuePut = " + valuePutLast + ").";
            }
        }

        // stop hazelcast
        hcInstance.getLifecycleService().terminate();

        // fail after stopping hazelcast instance
        if (msg != null) {
            Log(msg);
        }

        // fail if strict is required and assertion was violated
        if (strict && assertionViolationCount.get() > 0) {
            msg = "Assertion violated " + assertionViolationCount.get() + " times.";
            msg += " Assertion Recovery " + assertionRecoveryCount.get() + " times.";
            Log(msg);
        }
    }
    /**
     * Flush near cache.
     * <p>
     * Warning: this uses Hazelcast internals which might change from one version to the other.
     */
    private void flushNearCache(HazelcastInstance hcInstance) throws Exception {

        // get instance proxy
        HazelcastInstanceProxy hcInstanceProxy = (HazelcastInstanceProxy) hcInstance;

        // get internal implementation (using reflection)
        Field originalField = HazelcastInstanceProxy.class.getDeclaredField("original");
        originalField.setAccessible(true);
        HazelcastInstanceImpl hcImpl = (HazelcastInstanceImpl) originalField.get(hcInstanceProxy);

        // get node engine
        NodeEngineImpl nodeEngineImpl = hcImpl.node.nodeEngine;

        // get map service
        MapService mapService = nodeEngineImpl.getService(MapService.SERVICE_NAME);

        // clear near cache
        MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        NearCacheProvider nearCacheProvider = mapServiceContext.getNearCacheProvider();
        nearCacheProvider.destroyNearCache(mapName);
    }
    private void runTestInternal() throws Exception {

        // start 1 putter thread (put0)
        Thread threadPut = new Thread(new PutRunnable(), "put0");
        threadPut.start();

        // wait for putter thread to start before starting getter threads
        Thread.sleep(300);

        // start numGetters getter threads (get0-numGetters)
        List<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < numGetters; i++) {
            Thread thread = new Thread(new GetRunnable(), "get" + i);
            threads.add(thread);
        }
        for (Thread thread : threads) {
            thread.start();
        }

        // stop after maxRuntime seconds
        int i = 0;
        while (!stop.get() && i++ < maxRuntime) {
            Thread.sleep(1000);
        }
        if (!stop.get()) {
            Log("Problem did not occur within " + maxRuntime + "s.");
        }
        stop.set(true);
        threadPut.join();
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private class PutRunnable implements Runnable {

        @Override
        public void run() {
            Log(Thread.currentThread().getName() + " started.");
            int i = 0;
            while (!stop.get()) {
                i++;

                // put new value and update last state
                // note: the value in the map/near cache is *always* larger or equal to valuePut
                // assertion: valueMap >= valuePut
                map.put(key, i);
                valuePut.set(i);
                
                
                // check if we see our last update
                Integer valueMap = map.get(key);
               
                if (valueMap.intValue() != i) {
                    assertionViolationCount.incrementAndGet();
                    Log("Assertion violated! (valueMap = " + valueMap + ", i = " + i + ")");
                    // sleep to ensure near cache invalidation is really lost
                   
                    try {
                        Log("sleep and waitforit : "+waitforit+" to ensure near cache invalidation is really lost. (valueMap = " + valueMap + ", i = " + i + ")");
                        Thread.sleep(waitforit);
                    } catch (InterruptedException e) {
                        Log("Interrupted: " + e.getMessage());
                    }

                    // test again and stop if really lost
                    valueMap = map.get(key);
                    if (valueMap.intValue() != i) {
                        Log("Near cache invalidation is completely lost! (valueMap = " + valueMap + ", i = " + i + ")");
                       int tries = 0;
                        for (int j = 0; j < 100; j++) {
                             try {
                                Thread.sleep(waitforit);
                            } catch (InterruptedException e) {
                                Log("Interrupted: " + e.getMessage());
                            }
                            tries++;
                            valueMap = map.get(key);
                            if (valueMap.intValue() == i) {
                                break;
                            }
                        }
                        if (valueMap.intValue() != i){
                            Log("Near if cache invalidation is cannot be recovered! after " +tries+ " tries (valueMap = " + valueMap + ", i = " + i + ")");
                        }
                        failed.set(true);
                        stop.set(true);
                    }else {
                        assertionRecoveryCount.incrementAndGet();
                        Log("Near cache invalidation recovered! (valueMap = " + valueMap + ", i = " + i + ")");
                        
                    }
                }
            }
            Log(Thread.currentThread().getName() + " performed " + i + " operations.");
        }

    }

    class GetRunnable implements Runnable {
        @Override
        public void run() {
            Log(Thread.currentThread().getName() + " started.");
            int n = 0;
            while (!stop.get()) {
                n++;

                // blindly get the value - to trigger issue - and
                // parse the value - to get some CPU load
                Integer valueMapStr = map.get(key);
                Integer.parseInt(valueMapStr+"");
            }
            Log(Thread.currentThread().getName() + " performed " + n + " operations.");
        }

    }

}
