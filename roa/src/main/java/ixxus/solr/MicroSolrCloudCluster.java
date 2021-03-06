package ixxus.solr;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.embedded.SSLConfig;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient.Builder;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.cloud.OverseerCollectionMessageHandler;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CollectionParams.CollectionAction;
import org.apache.solr.common.params.CommonAdminParams;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ExecutorUtil;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrjNamedThreadFactory;
import org.apache.solr.core.CoreContainer;
import org.apache.zookeeper.KeeperException;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MicroSolrCloudCluster {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // TODO: make it configurable
  public static final int ZK_TIMEOUT = 3600000; // 1h

  public static final String DEFAULT_CLOUD_SOLR_XML = "<solr>\n" +
      "\n" +
      "  <str name=\"shareSchema\">${shareSchema:false}</str>\n" +
      "  <str name=\"configSetBaseDir\">${configSetBaseDir:configsets}</str>\n" +
      "  <str name=\"coreRootDirectory\">${coreRootDirectory:.}</str>\n" +
      "  <str name=\"collectionsHandler\">${collectionsHandler:solr.CollectionsHandler}</str>\n" +
      "\n" +
      "  <shardHandlerFactory name=\"shardHandlerFactory\" class=\"HttpShardHandlerFactory\">\n" +
      "    <str name=\"urlScheme\">${urlScheme:}</str>\n" +
      "    <int name=\"socketTimeout\">${socketTimeout:90000}</int>\n" +
      "    <int name=\"connTimeout\">${connTimeout:15000}</int>\n" +
      "  </shardHandlerFactory>\n" +
      "\n" +
      "  <solrcloud>\n" +
      "    <str name=\"host\">127.0.0.1</str>\n" +
      "    <int name=\"hostPort\">${hostPort:8983}</int>\n" +
      "    <str name=\"hostContext\">${hostContext:solr}</str>\n" +
      "    <int name=\"zkClientTimeout\">${solr.zkclienttimeout:30000}</int>\n" +
      "    <bool name=\"genericCoreNodeNames\">${genericCoreNodeNames:true}</bool>\n" +
      "    <int name=\"leaderVoteWait\">10000</int>\n" +
      "    <int name=\"distribUpdateConnTimeout\">${distribUpdateConnTimeout:45000}</int>\n" +
      "    <int name=\"distribUpdateSoTimeout\">${distribUpdateSoTimeout:340000}</int>\n" +
      "  </solrcloud>\n" +
      "  \n" +
      "</solr>\n";

  private ZkTestServer zkServer; // non-final due to injectChaos()
  private final boolean externalZkServer;
  private final List<JettySolrRunner> jettys = new CopyOnWriteArrayList<>();
  private final Path baseDir;
  private final CloudSolrClient solrClient;
  private final JettyConfig jettyConfig;

  private final ExecutorService executorLauncher = ExecutorUtil.newMDCAwareCachedThreadPool(new SolrjNamedThreadFactory("jetty-launcher"));
  private final ExecutorService executorCloser = ExecutorUtil.newMDCAwareCachedThreadPool(new SolrjNamedThreadFactory("jetty-closer"));

  private final AtomicInteger nodeIds = new AtomicInteger();

  /**
   * Create a MicroSolrCloudCluster with default solr.xml
   *
   * @param numServers  number of Solr servers to start
   * @param baseDir     base directory that the mini cluster should be run from
   * @param jettyConfig Jetty configuration
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, Path baseDir, JettyConfig jettyConfig) throws Exception {
    this(numServers, baseDir, DEFAULT_CLOUD_SOLR_XML, jettyConfig, null);
  }

  /**
   * Create a MicroSolrCloudCluster
   *
   * @param numServers          number of Solr servers to start
   * @param hostContext         context path of Solr servers used by Jetty
   * @param baseDir             base directory that the mini cluster should be run from
   * @param solrXml             solr.xml file to be uploaded to ZooKeeper
   * @param extraServlets       Extra servlets to be started by Jetty
   * @param extraRequestFilters extra filters to be started by Jetty
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, String hostContext, Path baseDir, String solrXml,
                               SortedMap<ServletHolder, String> extraServlets,
                               SortedMap<Class<? extends Filter>, String> extraRequestFilters) throws Exception {
    this(numServers, hostContext, baseDir, solrXml, extraServlets, extraRequestFilters, null);
  }

  /**
   * Create a MicroSolrCloudCluster
   *
   * @param numServers          number of Solr servers to start
   * @param hostContext         context path of Solr servers used by Jetty
   * @param baseDir             base directory that the mini cluster should be run from
   * @param solrXml             solr.xml file to be uploaded to ZooKeeper
   * @param extraServlets       Extra servlets to be started by Jetty
   * @param extraRequestFilters extra filters to be started by Jetty
   * @param sslConfig           SSL configuration
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, String hostContext, Path baseDir, String solrXml,
                               SortedMap<ServletHolder, String> extraServlets,
                               SortedMap<Class<? extends Filter>, String> extraRequestFilters,
                               SSLConfig sslConfig) throws Exception {
    this(numServers, baseDir, solrXml, JettyConfig.builder()
        .setContext(hostContext)
        .withSSLConfig(sslConfig)
        .withFilters(extraRequestFilters)
        .withServlets(extraServlets)
        .build());
  }

  /**
   * Create a MicroSolrCloudCluster
   *
   * @param numServers  number of Solr servers to start
   * @param baseDir     base directory that the mini cluster should be run from
   * @param solrXml     solr.xml file to be uploaded to ZooKeeper
   * @param jettyConfig Jetty configuration
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, Path baseDir, String solrXml, JettyConfig jettyConfig) throws Exception {
    this(numServers, baseDir, solrXml, jettyConfig, null);
  }

  /**
   * Create a MicroSolrCloudCluster
   *
   * @param numServers   number of Solr servers to start
   * @param baseDir      base directory that the mini cluster should be run from
   * @param solrXml      solr.xml file to be uploaded to ZooKeeper
   * @param jettyConfig  Jetty configuration
   * @param zkTestServer ZkTestServer to use.  If null, one will be created
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, Path baseDir, String solrXml, JettyConfig jettyConfig,
                               ZkTestServer zkTestServer) throws Exception {
    this(numServers, baseDir, solrXml, jettyConfig, zkTestServer, Optional.empty());
  }

  /**
   * Create a MicroSolrCloudCluster.
   * Note - this constructor visibility is changed to package protected so as to
   * discourage its usage. Ideally *new* functionality should use {@linkplain SolrCloudTestCase}
   * to configure any additional parameters.
   *
   * @param numServers   number of Solr servers to start
   * @param baseDir      base directory that the mini cluster should be run from
   * @param solrXml      solr.xml file to be uploaded to ZooKeeper
   * @param jettyConfig  Jetty configuration
   * @param zkTestServer ZkTestServer to use.  If null, one will be created
   * @param securityJson A string representation of security.json file (optional).
   * @throws Exception if there was an error starting the cluster
   */
  public MicroSolrCloudCluster(int numServers, Path baseDir, String solrXml, JettyConfig jettyConfig,
                               ZkTestServer zkTestServer, Optional<String> securityJson) throws Exception {

    Objects.requireNonNull(securityJson);
    this.baseDir = Objects.requireNonNull(baseDir);
    this.jettyConfig = Objects.requireNonNull(jettyConfig);

    log.info("Starting cluster of {} servers in {}", numServers, baseDir);

    Files.createDirectories(baseDir);

    this.externalZkServer = zkTestServer != null;
    if (!externalZkServer) {
      String zkDir = baseDir.resolve("zookeeper/server1/data").toString();
      zkTestServer = new ZkTestServer(zkDir);
      zkTestServer.run();
    }
    this.zkServer = zkTestServer;

    try (SolrZkClient zkClient = new SolrZkClient(zkServer.getZkHost(), ZK_TIMEOUT)) {
      zkClient.makePath("/solr/solr.xml", solrXml.getBytes(Charset.defaultCharset()), true);
      if (jettyConfig.sslConfig != null && jettyConfig.sslConfig.isSSLMode()) {
        zkClient.makePath("/solr" + ZkStateReader.CLUSTER_PROPS, "{'urlScheme':'https'}".getBytes(StandardCharsets.UTF_8), true);
      }
      if (securityJson.isPresent()) { // configure Solr security
        zkClient.makePath("/solr/security.json", securityJson.get().getBytes(Charset.defaultCharset()), true);
      }
    }

    // tell solr to look in zookeeper for solr.xml
    System.setProperty("zkHost", zkServer.getZkAddress());

    List<Callable<JettySolrRunner>> startups = new ArrayList<>(numServers);
    for (int i = 0; i < numServers; ++i) {
      startups.add(() -> startJettySolrRunner(newNodeName(), jettyConfig.context, jettyConfig));
    }

    Collection<Future<JettySolrRunner>> futures = executorLauncher.invokeAll(startups);
    Exception startupError = checkForExceptions("Error starting up MicroSolrCloudCluster", futures);
    if (startupError != null) {
      try {
        this.shutdown();
      } catch (Throwable t) {
        startupError.addSuppressed(t);
      }
      throw startupError;
    }

    waitForAllNodes(numServers, 60);
    for( JettySolrRunner jsr:  jettys) {
      log.info(String.format("PX56: started jetty '%s' on port: %d url: %s SOLR_HOME: %s" , jsr.getNodeName(), jsr.getLocalPort(), jsr.getBaseUrl(), jsr.getSolrHome()));
    }
    solrClient = buildSolrClient();
  }

  private void waitForAllNodes(int numServers, int timeout) throws IOException, InterruptedException {
    try (SolrZkClient zkClient = new SolrZkClient(zkServer.getZkHost(), ZK_TIMEOUT)) {
      int numliveNodes = 0;
      int retries = timeout;
      String liveNodesPath = "/solr/live_nodes";
      // Wait up to {timeout} seconds for number of live_nodes to match up number of servers
      do {
        if (zkClient.exists(liveNodesPath, true)) {
          numliveNodes = zkClient.getChildren(liveNodesPath, null, true).size();
          if (numliveNodes == numServers) {
            break;
          }
        }
        retries--;
        if (retries == 0) {
          throw new IllegalStateException("Solr servers failed to register with ZK."
              + " Current count: " + numliveNodes + "; Expected count: " + numServers);
        }

        Thread.sleep(1000);
      } while (numliveNodes != numServers);
    } catch (KeeperException e) {
      throw new IOException("Error communicating with zookeeper", e);
    }
  }

  public void waitForAllNodes(int timeout) throws IOException, InterruptedException {
    waitForAllNodes(jettys.size(), timeout);
  }

  private String newNodeName() {
    return "node" + nodeIds.incrementAndGet();
  }

  private Path createInstancePath(String name) throws IOException {
    Path instancePath = baseDir.resolve(name);
    Files.createDirectory(instancePath);
    return instancePath;
  }

  /**
   * @return ZooKeeper server used by the MiniCluster
   */
  public ZkTestServer getZkServer() {
    return zkServer;
  }

  /**
   * @return Unmodifiable list of all the currently started Solr Jettys.
   */
  public List<JettySolrRunner> getJettySolrRunners() {
    return Collections.unmodifiableList(jettys);
  }

  /**
   * @return a randomly-selected Jetty
   */
  public JettySolrRunner getRandomJetty(Random random) {
    int index = random.nextInt(jettys.size());
    return jettys.get(index);
  }

  /**
   * Start a new Solr instance
   *
   * @param hostContext         context path of Solr servers used by Jetty
   * @param extraServlets       Extra servlets to be started by Jetty
   * @param extraRequestFilters extra filters to be started by Jetty
   * @return new Solr instance
   */
  public JettySolrRunner startJettySolrRunner(String name, String hostContext,
                                              SortedMap<ServletHolder, String> extraServlets,
                                              SortedMap<Class<? extends Filter>, String> extraRequestFilters) throws Exception {
    return startJettySolrRunner(name, hostContext, extraServlets, extraRequestFilters, null);
  }

  /**
   * Start a new Solr instance
   *
   * @param hostContext         context path of Solr servers used by Jetty
   * @param extraServlets       Extra servlets to be started by Jetty
   * @param extraRequestFilters extra filters to be started by Jetty
   * @param sslConfig           SSL configuration
   * @return new Solr instance
   */
  public JettySolrRunner startJettySolrRunner(String name, String hostContext,
                                              SortedMap<ServletHolder, String> extraServlets,
                                              SortedMap<Class<? extends Filter>, String> extraRequestFilters, SSLConfig sslConfig) throws Exception {
    return startJettySolrRunner(name, hostContext, JettyConfig.builder()
        .withServlets(extraServlets)
        .withFilters(extraRequestFilters)
        .withSSLConfig(sslConfig)
        .build());
  }

  public JettySolrRunner getJettySolrRunner(int index) {
    return jettys.get(index);
  }

  /**
   * Start a new Solr instance on a particular servlet context
   *
   * @param name        the instance name
   * @param hostContext the context to run on
   * @param config      a JettyConfig for the instance's {@link org.apache.solr.client.solrj.embedded.JettySolrRunner}
   * @return a JettySolrRunner
   */
  public JettySolrRunner startJettySolrRunner(String name, String hostContext, JettyConfig config) throws Exception {
    Path runnerPath = createInstancePath(name);
    String context = getHostContextSuitableForServletContext(hostContext);
    JettyConfig newConfig = JettyConfig.builder(config).setContext(context).build();
    JettySolrRunner jetty = new JettySolrRunner(runnerPath.toString(), newConfig);
    jetty.start();
    jettys.add(jetty);
    return jetty;
  }

  /**
   * Start a new Solr instance, using the default config
   *
   * @return a JettySolrRunner
   */
  public JettySolrRunner startJettySolrRunner() throws Exception {
    return startJettySolrRunner(newNodeName(), jettyConfig.context, jettyConfig);
  }

  /**
   * Stop a Solr instance
   *
   * @param index the index of node in collection returned by {@link #getJettySolrRunners()}
   * @return the shut down node
   */
  public JettySolrRunner stopJettySolrRunner(int index) throws Exception {
    JettySolrRunner jetty = jettys.get(index);
    jetty.stop();
    jettys.remove(index);
    return jetty;
  }

  /**
   * Add a previously stopped node back to the cluster
   *
   * @param jetty a {@link JettySolrRunner} previously returned by {@link #stopJettySolrRunner(int)}
   * @return the started node
   * @throws Exception on error
   */
  public JettySolrRunner startJettySolrRunner(JettySolrRunner jetty) throws Exception {
    jetty.start(false);
    jettys.add(jetty);
    return jetty;
  }

  protected JettySolrRunner stopJettySolrRunner(JettySolrRunner jetty) throws Exception {
    jetty.stop();
    return jetty;
  }

  /**
   * @deprecated Use {@link #uploadConfigSet(Path, String)}
   */
  @Deprecated
  public void uploadConfigDir(File configDir, String configName) throws IOException, KeeperException, InterruptedException {
    uploadConfigSet(configDir.toPath(), configName);
  }

  /**
   * Upload a config set
   *
   * @param configDir  a path to the config set to upload
   * @param configName the name to give the configset
   */
  public void uploadConfigSet(Path configDir, String configName) throws IOException, KeeperException, InterruptedException {
    try (SolrZkClient zkClient = new SolrZkClient(zkServer.getZkAddress(),
        ZK_TIMEOUT, ZK_TIMEOUT, null)) {
      ZkConfigManager manager = new ZkConfigManager(zkClient);
      manager.uploadConfigDir(configDir, configName);
    }
  }

  public void deleteAllCollections() throws Exception {
    try (ZkStateReader reader = new ZkStateReader(solrClient.getZkStateReader().getZkClient())) {
      reader.createClusterStateWatchersAndUpdate();
      for (String collection : reader.getClusterState().getCollectionStates().keySet()) {
        CollectionAdminRequest.deleteCollection(collection).process(solrClient);
      }
    }
  }

  /**
   * @deprecated Use {@link CollectionAdminRequest#createCollection(String, String, int, int)}
   */
  @Deprecated
  public NamedList<Object> createCollection(String name, int numShards, int replicationFactor,
                                            String configName, Map<String, String> collectionProperties) throws SolrServerException, IOException {
    return createCollection(name, numShards, replicationFactor, configName, null, null, collectionProperties);
  }

  /**
   * @deprecated Use {@link CollectionAdminRequest#createCollection(String, String, int, int)}
   */
  @Deprecated
  public NamedList<Object> createCollection(String name, int numShards, int replicationFactor,
                                            String configName, String createNodeSet, String asyncId, Map<String, String> collectionProperties) throws SolrServerException, IOException {
    final ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CoreAdminParams.ACTION, CollectionAction.CREATE.name());
    params.set(CoreAdminParams.NAME, name);
    params.set("numShards", numShards);
    params.set("replicationFactor", replicationFactor);
    params.set("collection.configName", configName);
    if (null != createNodeSet) {
      params.set(OverseerCollectionMessageHandler.CREATE_NODE_SET, createNodeSet);
    }
    if (null != asyncId) {
      params.set(CommonAdminParams.ASYNC, asyncId);
    }
    if (collectionProperties != null) {
      for (Map.Entry<String, String> property : collectionProperties.entrySet()) {
        params.set(CoreAdminParams.PROPERTY_PREFIX + property.getKey(), property.getValue());
      }
    }

    return makeCollectionsRequest(params);
  }

  /**
   * @deprecated use {@link CollectionAdminRequest#deleteCollection(String)}
   */
  @Deprecated
  public NamedList<Object> deleteCollection(String name) throws SolrServerException, IOException {
    final ModifiableSolrParams params = new ModifiableSolrParams();
    params.set(CoreAdminParams.ACTION, CollectionAction.DELETE.name());
    params.set(CoreAdminParams.NAME, name);

    return makeCollectionsRequest(params);
  }

  private NamedList<Object> makeCollectionsRequest(final ModifiableSolrParams params) throws SolrServerException, IOException {

    final QueryRequest request = new QueryRequest(params);
    request.setPath("/admin/collections");

    return solrClient.request(request);
  }

  /**
   * Shut down the cluster, including all Solr nodes and ZooKeeper
   */
  public void shutdown() throws Exception {
    try {

      IOUtils.closeQuietly(solrClient);
      // accept no new tasks
      executorLauncher.shutdown();
      List<Callable<JettySolrRunner>> shutdowns = new ArrayList<>(jettys.size());
      for (final JettySolrRunner jetty : jettys) {
        shutdowns.add(() -> stopJettySolrRunner(jetty));
      }
      jettys.clear();
      Collection<Future<JettySolrRunner>> futures = executorCloser.invokeAll(shutdowns);
      Exception shutdownError = checkForExceptions("Error shutting down MicroSolrCloudCluster", futures);
      if (shutdownError != null) {
        throw shutdownError;
      }
    } finally {
      ExecutorUtil.shutdownAndAwaitTermination(executorLauncher);
      ExecutorUtil.shutdownAndAwaitTermination(executorCloser);
      try {
        if (!externalZkServer) {
          zkServer.shutdown();
        }
      } finally {
        System.clearProperty("zkHost");
      }
    }
  }

  public Path getBaseDir() {
    return baseDir;
  }

  public CloudSolrClient getSolrClient() {
    return solrClient;
  }

  public SolrZkClient getZkClient() {
    return solrClient.getZkStateReader().getZkClient();
  }

  protected CloudSolrClient buildSolrClient() {
    return new Builder()
        .withZkHost(getZkServer().getZkAddress())
        .build();
  }

  private static String getHostContextSuitableForServletContext(String ctx) {
    if (ctx == null || "".equals(ctx)) ctx = "/solr";
    if (ctx.endsWith("/")) ctx = ctx.substring(0, ctx.length() - 1);
    if (!ctx.startsWith("/")) ctx = "/" + ctx;
    return ctx;
  }

  private Exception checkForExceptions(String message, Collection<Future<JettySolrRunner>> futures) throws InterruptedException {
    Exception parsed = new Exception(message);
    boolean ok = true;
    for (Future<JettySolrRunner> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        parsed.addSuppressed(e.getCause());
        ok = false;
      } catch (InterruptedException e) {
        Thread.interrupted();
        throw e;
      }
    }
    return ok ? null : parsed;
  }

  /**
   * Return the jetty that a particular replica resides on
   */
  public JettySolrRunner getReplicaJetty(Replica replica) {
    for (JettySolrRunner jetty : jettys) {
      if (replica.getCoreUrl().startsWith(jetty.getBaseUrl().toString()))
        return jetty;
    }
    throw new IllegalArgumentException("Cannot find Jetty for a replica with core url " + replica.getCoreUrl());
  }

  /**
   * Make the zookeeper session on a particular jetty expire
   */
  public void expireZkSession(JettySolrRunner jetty) {
    CoreContainer cores = jetty.getCoreContainer();
    if (cores != null) {
      SolrZkClient zkClient = cores.getZkController().getZkClient();
      zkClient.getSolrZooKeeper().closeCnxn();
      long sessionId = zkClient.getSolrZooKeeper().getSessionId();
      zkServer.expire(sessionId);
      log.info("Expired zookeeper session {} from node {}", sessionId, jetty.getBaseUrl());
    }
  }

}
