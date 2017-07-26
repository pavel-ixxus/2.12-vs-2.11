package ixxus.solr;

import org.apache.solr.common.SolrInputDocument;

import java.nio.file.Files;
import java.nio.file.Path;

public class MicroUtils {

  public static SolrInputDocument solrDoc(Object... fieldsAndValues) {
    SolrInputDocument sd = new SolrInputDocument();
    for (int i = 0; i < fieldsAndValues.length; i += 2) {
      sd.addField((String) fieldsAndValues[i], fieldsAndValues[i + 1]);
    }
    return sd;
  }

  public static Path createTempDir() {
    Path d;
    try {
      d = Files.createTempDirectory("micro-solr-");
    } catch (Exception x) {
      throw new RuntimeException(x);
    }
    return d;
  }
}

