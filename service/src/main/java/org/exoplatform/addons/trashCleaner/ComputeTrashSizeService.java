package org.exoplatform.addons.trashCleaner;

import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.rest.resource.ResourceContainer;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

@Path("computeTrashSize")
public class ComputeTrashSizeService implements ResourceContainer {

  private static final Log LOG = ExoLogger.getLogger(ComputeTrashSizeService.class);
  int nbFiles;
  long size;


  @GET
  @RolesAllowed("administrators")
  public Response computeTrashSize(JobExecutionContext jobExecutionContext) throws JobExecutionException {
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
    String result = "Compute Trash size successfully. There are "+nbFiles+" files in trash, with a size of "+humanReadableByteCountBin(size)+"!";
    LOG.info(result);
    return Response.ok(result).build();

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
