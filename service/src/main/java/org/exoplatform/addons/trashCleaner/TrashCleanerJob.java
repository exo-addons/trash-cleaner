package org.exoplatform.addons.trashCleaner;


import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.actions.ActionServiceContainer;
import org.exoplatform.services.cms.documents.TrashService;
import org.exoplatform.services.cms.relations.RelationsService;
import org.exoplatform.services.cms.taxonomy.TaxonomyService;
import org.exoplatform.services.cms.thumbnail.ThumbnailService;
import org.exoplatform.services.jcr.RepositoryService;

import org.exoplatform.ecm.webui.utils.Utils;
import org.exoplatform.ecm.webui.utils.PermissionUtil;
import org.exoplatform.services.jcr.core.ManageableRepository;
import org.exoplatform.services.jcr.ext.common.SessionProvider;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import javax.jcr.*;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeType;
import java.util.Calendar;

/**
 * Created by Romain Dénarié (romain.denarie@exoplatform.com) on 22/01/16.
 */
public class TrashCleanerJob implements Job {

    private static final Log LOG = ExoLogger.getLogger(TrashCleanerJob.class);

    public TrashCleanerJob() {
    }

    public void execute(JobExecutionContext context) throws JobExecutionException {
        String timeLimit = System.getProperty("trashcleaner.lifetime");
        if (timeLimit == null) timeLimit = "30";
        LOG.info("Start TrashCleanerJob, delete nodes in trash older than "+timeLimit+" days.");
        TrashService trashService = (TrashService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TrashService.class);
	    int deletedNode = 0;
        Node trashNode = trashService.getTrashHomeNode();
        try {
            if (trashNode.hasNodes()){
                NodeIterator childNodes = trashNode.getNodes();
                long size = childNodes.getSize();
                int current = 1;

                while (childNodes.hasNext()){
                    Node currentNode = (Node) childNodes.next();
                    try {
                        if (current % 20 == 0) {
                            LOG.info("Checking node " + currentNode.getName() + " node from Trash ("+current+"/"+size+")");
                        }
                        if (currentNode.getName().equals("exo:actions") && currentNode.hasNode("trashFolder")) {
                            continue;
                        }
                        if (currentNode.hasProperty("exo:lastModifiedDate")) {
                            long dateCreated = currentNode.getProperty("exo:lastModifiedDate").getDate().getTimeInMillis();
                            if ((Calendar.getInstance().getTimeInMillis() - dateCreated > Long.parseLong(timeLimit)*24*60*60*1000) && (currentNode.isNodeType("exo:restoreLocation"))){
                                deleteNode(currentNode, trashService);
				                deletedNode++;
                            }
                        } else {
                            deleteNode(currentNode, trashService);
			                deletedNode++;
                        }
                        current++;
                    } catch(Exception ex){
                        LOG.info("Error while removing " + currentNode.getName() + " node from Trash", ex);
                    }
                }
            }
        } catch (RepositoryException ex){
            LOG.info("Failed to get child nodes", ex);
        }
        LOG.info("Empty Trash folder successfully! "+deletedNode+" nodes deleted");
    }

    private static void removeMixins(Node node) throws Exception {
        NodeType[] mixins = node.getMixinNodeTypes();
        for (NodeType nodeType : mixins) {
            node.removeMixin(nodeType.getName());
        }
    }

    public void deleteNode(Node node, TrashService trashService) throws Exception{
        TaxonomyService taxonomyService = (TaxonomyService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TaxonomyService.class);
        ActionServiceContainer actionService = (ActionServiceContainer) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(ActionServiceContainer.class);
        ThumbnailService thumbnailService = (ThumbnailService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(ThumbnailService.class);
        RepositoryService repoService = (RepositoryService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);
        RelationsService relationService = (RelationsService) ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RelationsService.class);
        Session session = node.getSession();
        Node parentNode = node.getParent();
        try{
            try{
                PropertyIterator iter = node.getReferences();
                while (iter.hasNext()){
                    Node refNode = iter.nextProperty().getParent();
                    relationService.removeRelation(refNode, node.getPath());
                }
            } catch(Exception ex){
                LOG.info("An error occurs while removing relations", ex);
            }

            try{
                actionService.removeAction(node, repoService.getCurrentRepository().getConfiguration().getName());
            } catch(Exception ex){
                LOG.info("An error occurs while removing actions ", ex);
            }
            try {
                thumbnailService.processRemoveThumbnail(node);
            } catch(Exception ex){
                LOG.info("An error occurs while removing thumbnail ", ex);
            }
            try{
                if (PermissionUtil.canRemoveNode(node) && node.isNodeType(Utils.EXO_AUDITABLE)) {
                    removeAuditForNode(node, repoService.getCurrentRepository());
                }
            } catch(Exception ex){
                LOG.info("An error occurs while removing audit ", ex);
            }
            checkReferencesOfChildNode(node,relationService);
            node.remove();
            parentNode.getSession().save();
        } catch(ReferentialIntegrityException ref){
	        //LOG.info("ReferentialIntegrityException when removing " + node.getName() + " node from Trash", ref);
            session.refresh(false);
        } catch (ConstraintViolationException cons) {
	        //LOG.info("ConstraintViolationException when removing " + node.getName() + " node from Trash", cons);
            session.refresh(false);
        } catch(Exception ex){
            LOG.info("Error while removing " + node.getName() + " node from Trash", ex);
        }
        return;
    }

    private void removeAuditForNode(Node node, ManageableRepository repository) throws Exception {
        Session session = SessionProvider.createSystemProvider().getSession(node.getSession().getWorkspace().getName(), repository);
        if (session.getRootNode().hasNode("exo:audit") &&
                session.getRootNode().getNode("exo:audit").hasNode(node.getUUID())) {
            session.getRootNode().getNode("exo:audit").getNode(node.getUUID()).remove();
            session.save();
        }
    }

    private void checkReferencesOfChildNode (Node node, RelationsService relationService) throws Exception {
        NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            try{
                PropertyIterator iter = child.getReferences();
                while (iter.hasNext()){
                    Node refNode = iter.nextProperty().getParent();
                    LOG.warn("Node "+refNode.getPath()+" references node "+child.getPath()+". Should remove this reference before removing node.");
                }
                checkReferencesOfChildNode(child,relationService);
            } catch(Exception ex){
                LOG.info("An error occurs while removing relations", ex);
            }

        }

    }
}
