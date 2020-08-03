package org.exoplatform.addons.trashCleaner;


import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.services.cms.actions.ActionServiceContainer;
import org.exoplatform.services.cms.documents.TrashService;
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
import org.quartz.DisallowConcurrentExecution;

import javax.jcr.*;
import javax.jcr.nodetype.ConstraintViolationException;
import java.util.Calendar;

/**
 * Created by Romain Dénarié (romain.denarie@exoplatform.com) on 22/01/16.
 */
@DisallowConcurrentExecution
public class TrashCleanerJob implements Job {

    private static final Log LOG = ExoLogger.getLogger(TrashCleanerJob.class);

    public TrashCleanerJob() {
    }

    public void execute(JobExecutionContext context) throws JobExecutionException {
        String timeLimit = System.getProperty("trashcleaner.lifetime");
        if (timeLimit == null) timeLimit = "30";
        LOG.info("Start TrashCleanerJob, delete nodes in trash older than "+timeLimit+" days.");
        TrashService trashService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(TrashService.class);
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
                        } else {
                            LOG.debug("Checking node " + currentNode.getName() + " node from Trash ("+current+"/"+size+")");
                        }
                        if (currentNode.getName().equals("exo:actions") && currentNode.hasNode("trashFolder")) {
                            continue;
                        }
                        if (currentNode.hasProperty("exo:lastModifiedDate")) {
                            long dateCreated = currentNode.getProperty("exo:lastModifiedDate").getDate().getTimeInMillis();
                            if ((Calendar.getInstance().getTimeInMillis() - dateCreated > Long.parseLong(timeLimit)*24*60*60*1000) && (currentNode.isNodeType("exo:restoreLocation"))){
                                deleteNode(currentNode);
				                deletedNode++;
                            }
                        } else {
                            deleteNode(currentNode);
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

    public void deleteNode(Node node) throws Exception{
        ActionServiceContainer actionService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(ActionServiceContainer.class);
        ThumbnailService thumbnailService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(ThumbnailService.class);
        RepositoryService repoService = ExoContainerContext.getCurrentContainer().getComponentInstanceOfType(RepositoryService.class);
        Session session = node.getSession();
        Node parentNode = node.getParent();
        LOG.debug("Try to delete node "+node.getPath());
        try{
            try{
                removeReferences(node);
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
            node.remove();
            parentNode.save();
            LOG.debug("Node "+node.getPath() + " deleted");
        } catch(ReferentialIntegrityException ref){
	        LOG.warn("ReferentialIntegrityException when removing " + node.getName() + " node from Trash", ref);
            session.refresh(false);
        } catch (ConstraintViolationException cons) {
	        LOG.error("ConstraintViolationException when removing " + node.getName() + " node from Trash", cons);
            session.refresh(false);
        } catch(Exception ex){
            LOG.error("Error while removing " + node.getName() + " node from Trash", ex);
        }
    }

    private void removeReferences(Node node) throws Exception {
        PropertyIterator iter = node.getReferences();
        if (iter.hasNext()){
            //if there is a reference, move it
            LOG.debug("Node "+node.getPath()+" is referenced by "+iter.nextProperty().getPath()+", move it to trash root");
            node.getSession().move(node.getPath(), "/Trash/"+node.getName());
            //if we do something with this node, no need to go deeper
            return;
        }

        NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            Node child = children.nextNode();
            removeReferences(child);
        }

    }

    private void removeAuditForNode(Node node, ManageableRepository repository) throws Exception {
        Session session = SessionProvider.createSystemProvider().getSession(node.getSession().getWorkspace().getName(), repository);
        if (session.getRootNode().hasNode("exo:audit") &&
                session.getRootNode().getNode("exo:audit").hasNode(node.getUUID())) {
            session.getRootNode().getNode("exo:audit").getNode(node.getUUID()).remove();
            session.save();
        }
    }

}
