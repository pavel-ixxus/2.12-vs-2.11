package ixxus.solr

import java.nio.file.Path
import com.typesafe.scalalogging.StrictLogging

case class MicroSolrCloud(configPath: Path,
                          configName: String = "data_driven_schema_configs",
                          nodeCount: Int = 1)
  extends StrictLogging {

  def nodeCount(numShards: Int,
                numReplicas: Int,
                maxShardsPerNode: Int) =
    (numShards * numReplicas + (maxShardsPerNode - 1)) / maxShardsPerNode

  System.setProperty("SOLR_LOG_LEVEL", "FINEST")

  val cluster: MicroSolrCloudCluster =
    new MicroSolrClusterBuilder(nodeCount, MicroUtils.createTempDir())
      .addConfig(configName, configPath)
      .configure()

  def shutdown() = cluster.shutdown()

}
