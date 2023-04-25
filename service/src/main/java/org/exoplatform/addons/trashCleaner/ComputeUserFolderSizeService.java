package org.exoplatform.addons.trashCleaner;

import org.exoplatform.commons.serialization.B;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.config.RepositoryConfigurationException;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.app.SessionProviderService;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.jcr.ext.distribution.DataDistributionManager;
import org.exoplatform.services.jcr.ext.distribution.DataDistributionMode;
import org.exoplatform.services.jcr.ext.distribution.DataDistributionType;
import org.exoplatform.services.jcr.ext.hierarchy.NodeHierarchyCreator;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserStatus;
import org.exoplatform.services.rest.resource.ResourceContainer;

import javax.annotation.security.RolesAllowed;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import io.swagger.v3.oas.annotations.Parameter;

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

@Path("computeUserFolderSize")
public class ComputeUserFolderSizeService implements ResourceContainer {
  private static final Log LOG = ExoLogger.getLogger(ComputeUserFolderSizeService.class);
  RepositoryService        repositoryService;
  SessionProviderService sessionProviderService;
  OrganizationService organizationService;
  NodeHierarchyCreator nodeHierarchyCreator;
  DataDistributionManager dataDistributionManager;

  Node userNodes;

  private final int BATCH_SIZE = 500;

  public ComputeUserFolderSizeService(DataDistributionManager dataDistributionManager, NodeHierarchyCreator nodeHierarchyCreator,OrganizationService organizationService,RepositoryService repositoryService, SessionProviderService sessionProviderService) {
    this.repositoryService = repositoryService;
    this.organizationService = organizationService;
    this.nodeHierarchyCreator = nodeHierarchyCreator;
    this.sessionProviderService = sessionProviderService;
    this.dataDistributionManager = dataDistributionManager;
  }

  @GET
  @RolesAllowed("administrators")
  public Response computeUserFolderSizeSize(@Parameter(description = "Check for user not connected since this date (format timestamp in ms)") @QueryParam("date") String date) {
    var ref = new Object() {
      Instant limitDate = Instant.now();
    };
    if (date == null || date.equals("")) {
      ref.limitDate = ref.limitDate.minus(365*2, ChronoUnit.DAYS);
    } else {
      ref.limitDate = Instant.ofEpochMilli(Long.parseLong(date));
    }
    LOG.info("Compute Users Folder size for user not connected since {}", ref.limitDate);
    int totalSize = 0;
    long startTime = System.currentTimeMillis();

    try {
      ListAccess<User> users = organizationService.getUserHandler().findAllUsers(UserStatus.ANY);
      int current = 0;
      int total = users.getSize();
      User[] usersArray;
      do {
        RequestLifeCycle.begin(ExoContainerContext.getCurrentContainer());

        long startTimeLoadUsers = System.currentTimeMillis();
        usersArray = users.load(current, Math.min(total - current, BATCH_SIZE));
        LOG.debug("Load {} users in {} ms", Math.min(total - current, BATCH_SIZE), System.currentTimeMillis() - startTimeLoadUsers);

        long startTimeTreatment = System.currentTimeMillis();
        totalSize += Arrays.stream(usersArray).filter(user -> {
          if (isConnected(user)) {
            Instant lastLoginTime = user.getLastLoginTime().toInstant();
            if (lastLoginTime.isBefore(ref.limitDate)) {
              LOG.debug("User {} lastLoginTime ({}) is before limitDate ({}), need to compute size", user.getUserName(), user.getLastLoginTime(), ref.limitDate);
              return true;
            }
          } else {
            Instant createdDate = user.getCreatedDate().toInstant();
            if (createdDate.isBefore(ref.limitDate)) {
              LOG.debug("User {} never connected and created ({}) before limitDate ({}) , need to compute size", user.getUserName(), user.getCreatedDate(), ref.limitDate);
              return true;
            }
          }
          return false;
        }).reduce(0, (subtotal, user) -> {
          //compute user folder size
          int userFolderSize = computeUserFolderSize(user.getUserName());
          return subtotal+userFolderSize;
        }, Integer::sum);

        LOG.info("Treat {} users ({}/{}) in {} ms", Math.min(total - current, BATCH_SIZE),current+usersArray.length,total, System.currentTimeMillis() - startTimeLoadUsers);

        current+=usersArray.length;
        RequestLifeCycle.end();

      } while (usersArray.length == BATCH_SIZE);

      String result = "Total size for user not connected since "+ref.limitDate.toString()+" is "+humanReadableByteCountBin(totalSize)+", execution time "+(System.currentTimeMillis() - startTime)+" ms";

      LOG.info(result);
      return Response.ok(result).build();

    } catch (Exception e) {
      LOG.error("Error when searching users",e);
      return Response.serverError().build();
    }
  }

  private int computeUserFolderSize(String userName) {
    try {
      Node userNode = getUserNode(this.sessionProviderService.getSystemSessionProvider(null), userName);
      return computeSubFolderSize(userNode);
    } catch (PathNotFoundException pe) {
      //user folder not exists
      return 0;
    } catch (Exception e) {
      LOG.error("Unable to compute user node size for user {}", userName,e);
      return 0;
    }
  }

  private Node getUserNode(SessionProvider systemSessionProvider, String userName) throws
                                                                                   RepositoryException,
                                                                                   RepositoryConfigurationException {

    if (this.userNodes==null) {
      Session session = systemSessionProvider.getSession("collaboration", (ManageableRepository) repositoryService.getDefaultRepository());
      this.userNodes = (Node)session.getItem("/Users");
    }
    DataDistributionType dataDistributionType = dataDistributionManager.getDataDistributionType(DataDistributionMode.READABLE);
    return dataDistributionType.getDataNode(this.userNodes,userName);
  }

  private boolean isConnected(User user) {
    return (user.getLastLoginTime() != null && !user.getCreatedDate().equals(user.getLastLoginTime()));
  }

  private int computeSubFolderSize(Node node) throws RepositoryException {
    NodeIterator childNodes = node.getNodes();
    int size = 0;
    while (childNodes.hasNext()){
      Node currentNode = (Node) childNodes.next();
      if (currentNode.isNodeType("nt:file")) {
        Node content=currentNode.getNode("jcr:content");
        size += getContentSize(content);
      } else if (currentNode.isNodeType("nt:folder") || currentNode.isNodeType("nt:unstructured")) {
        computeSubFolderSize(currentNode);
      }
    }
    return size;
  }

  public int getContentSize(Node content) throws RepositoryException {
    try {
      return content.getProperty("jcr:data").getValue().getStream().readAllBytes().length;
    } catch (Exception e) {
      LOG.error("Unable to compute size for node {}", content.getPath());
      return 0;
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
