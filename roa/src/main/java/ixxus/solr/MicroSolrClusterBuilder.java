package ixxus.solr;

import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.ZkClientClusterStateProvider;
import org.apache.solr.common.cloud.ClusterProperties;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class MicroSolrClusterBuilder {
  private static class Config {
    final String name;
    final Path path;

    private Config(String name, Path path) {
      this.name = name;
      this.path = path;
    }
  }

  private final int nodeCount;
  private final Path baseDir;
  private String solrxml = MicroSolrCloudCluster.DEFAULT_CLOUD_SOLR_XML;
  private JettyConfig jettyConfig = buildJettyConfig("/solr");
  private Optional<String> securityJson = Optional.empty();

  private List<Config> configs = new ArrayList<>();
  private Map<String, String> clusterProperties = new HashMap<>();
  static volatile SSLTestConfig sslConfig;

  protected static JettyConfig buildJettyConfig(String context) {
    return JettyConfig.builder().setContext(context).withSSLConfig(sslConfig).build();
  }

  /**
   * Create a builder
   *
   * @param nodeCount the number of nodes in the cluster
   * @param baseDir   a base directory for the cluster
   */
  public MicroSolrClusterBuilder(int nodeCount, Path baseDir) {
    this.nodeCount = nodeCount;
    this.baseDir = baseDir;
  }

  /**
   * Use a {@link JettyConfig} to configure the cluster's jetty servers
   */
  public MicroSolrClusterBuilder withJettyConfig(JettyConfig jettyConfig) {
    this.jettyConfig = jettyConfig;
    return this;
  }

  /**
   * Use the provided string as solr.xml content
   */
  public MicroSolrClusterBuilder withSolrXml(String solrXml) {
    this.solrxml = solrXml;
    return this;
  }

  /**
   * Read solr.xml from the provided path
   */
  public MicroSolrClusterBuilder withSolrXml(Path solrXml) {
    try {
      this.solrxml = new String(Files.readAllBytes(solrXml), Charset.defaultCharset());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return this;
  }

  /**
   * Configure the specified security.json for the {@linkplain MicroSolrClusterBuilder}
   *
   * @param securityJson The path specifying the security.json file
   * @return the instance of {@linkplain MicroSolrClusterBuilder}
   */
  public MicroSolrClusterBuilder withSecurityJson(Path securityJson) {
    try {
      this.securityJson = Optional.of(new String(Files.readAllBytes(securityJson), Charset.defaultCharset()));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return this;
  }

  /**
   * Configure the specified security.json for the {@linkplain MicroSolrClusterBuilder}
   *
   * @param securityJson The string specifying the security.json configuration
   * @return the instance of {@linkplain MicroSolrClusterBuilder}
   */
  public MicroSolrClusterBuilder withSecurityJson(String securityJson) {
    this.securityJson = Optional.of(securityJson);
    return this;
  }

  /**
   * Upload a collection config before tests start
   *
   * @param configName the config name
   * @param configPath the path to the config files
   */
  public MicroSolrClusterBuilder addConfig(String configName, Path configPath) {
    this.configs.add(new Config(configName, configPath));
    return this;
  }

  /**
   * Set a cluster property
   *
   * @param propertyName  the property name
   * @param propertyValue the property value
   */
  public MicroSolrClusterBuilder withProperty(String propertyName, String propertyValue) {
    this.clusterProperties.put(propertyName, propertyValue);
    return this;
  }

  /**
   * Configure and run the {@link MicroSolrCloudCluster}
   *
   * @throws Exception if an error occurs on startup
   */
  public MicroSolrCloudCluster configure() throws Exception {
    MicroSolrCloudCluster cluster =
        new MicroSolrCloudCluster(nodeCount, baseDir, solrxml, jettyConfig, null, securityJson);
    CloudSolrClient client = cluster.getSolrClient();
    for (Config config : configs) {
      ((ZkClientClusterStateProvider) client.getClusterStateProvider()).uploadConfig(config.path, config.name);
    }
    if (clusterProperties.size() > 0) {
      ClusterProperties props = new ClusterProperties(cluster.getSolrClient().getZkStateReader().getZkClient());
      for (Map.Entry<String, String> entry : clusterProperties.entrySet()) {
        props.setClusterProperty(entry.getKey(), entry.getValue());
      }
    }
    return cluster;
  }


}
