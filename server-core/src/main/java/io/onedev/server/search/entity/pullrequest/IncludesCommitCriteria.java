package io.onedev.server.search.entity.pullrequest;

import java.util.Collection;

import javax.annotation.Nullable;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.eclipse.jgit.lib.ObjectId;

import io.onedev.server.OneDev;
import io.onedev.server.cache.CodeCommentRelationInfoManager;
import io.onedev.server.model.Project;
import io.onedev.server.model.PullRequest;
import io.onedev.server.model.User;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.util.ProjectAwareCommitId;
import io.onedev.server.util.PullRequestConstants;

public class IncludesCommitCriteria extends PullRequestCriteria {

	private static final long serialVersionUID = 1L;

	private final Project project;
	
	private final ObjectId commitId;
	
	private final String value;
	
	public IncludesCommitCriteria(@Nullable Project project, String value) {
		ProjectAwareCommitId commitId = EntityQuery.getCommitId(project, value);
		this.project = commitId.getProject();
		this.commitId = commitId.getCommitId();
		this.value = value;
	}
	
	@Override
	public Predicate getPredicate(Root<PullRequest> root, CriteriaBuilder builder, User user) {
		Collection<Long> pullRequestIds = getPullRequestIds();
		if (!pullRequestIds.isEmpty()) {
			return builder.and(
					builder.equal(root.get(PullRequestConstants.ATTR_TARGET_PROJECT), project),
					root.get(PullRequestConstants.ATTR_ID).in(pullRequestIds));
		} else {
			return builder.disjunction();
		}
	}
	
	private Collection<Long> getPullRequestIds() {
		return OneDev.getInstance(CodeCommentRelationInfoManager.class).getPullRequestIds(project, commitId);		
	}
	
	@Override
	public boolean matches(PullRequest request, User user) {
		return request.getTargetProject().equals(project) && getPullRequestIds().contains(request.getId());
	}

	@Override
	public boolean needsLogin() {
		return false;
	}

	@Override
	public String toString() {
		return PullRequestQuery.getRuleName(PullRequestQueryLexer.IncludesCommit) + " " 
				+ PullRequestQuery.quote(value);
	}

}