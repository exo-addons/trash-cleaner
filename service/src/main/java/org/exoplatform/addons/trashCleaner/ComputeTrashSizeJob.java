package org.exoplatform.addons.trashCleaner;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.documents.TrashService;
import org.quartz.Job;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

public class ComputeTrashSizeJob implements Job {

  private static final Log LOG = ExoLogger.getLogger(ComputeTrashSizeJob.class);
  int nbFiles;
  long size;


  @Override
  public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
    LOG.info("Compute Trash size.");
    TrashService trashService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TrashService.class);
    nbFiles = 0;
    size = 0;
    Node trashNode = trashService.getTrashHomeNode();

    try {

      if (trashNode.hasNodes()){
        computeSubFolderSize(trashNode);
      }
    } catch (RepositoryException ex){
      LOG.info("Failed to get child nodes", ex);
    }
    LOG.info("Compute Trash size successfully. There are {} files in trash, with a size of {}!", nbFiles, humanReadableByteCountBin(size));
  }

  private void computeSubFolderSize(Node node) throws RepositoryException {
    NodeIterator childNodes = node.getNodes();

    while (childNodes.hasNext()){
      Node currentNode = (Node) childNodes.next();
      if (currentNode.isNodeType("nt:file")) {
        nbFiles++;
        Node content=currentNode.getNode("jcr:content");
        try {
          size += content.getProperty("jcr:data").getValue().getStream().readAllBytes().length;
        } catch (IOException e) {
          LOG.error("Unable to read size for node {}",currentNode.getPath());
        }
      } else if (currentNode.isNodeType("nt:folder") || currentNode.isNodeType("nt:unstructured")) {
        computeSubFolderSize(currentNode);
      }
    }
  }

  public static String humanReadableByteCountBin(long bytes) {
    long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
    if (absB < 1024) {
      return bytes + " B";
    }
    long value = absB;
    CharacterIterator ci = new StringCharacterIterator("KMGTPE");
    for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
      value >>= 10;
      ci.next();
    }
    value *= Long.signum(bytes);
    return String.format("%.1f %ciB", value / 1024.0, ci.current());
  }
}
