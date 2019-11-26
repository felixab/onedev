package io.onedev.server.search.entity.project;

import java.util.List;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.search.entity.EntityCriteria;
import io.onedev.server.search.entity.OrCriteriaHelper;
import io.onedev.server.search.entity.ParensAware;

public class OrCriteria extends EntityCriteria<Project> implements ParensAware {
	
	private static final long serialVersionUID = 1L;

	private final List<EntityCriteria<Project>> criterias;
	
	public OrCriteria(List<EntityCriteria<Project>> criterias) {
		this.criterias = criterias;
	}

	@Override
	public Predicate getPredicate(Root<Project> root, CriteriaBuilder builder, User user) {
		return new OrCriteriaHelper<Project>(criterias).getPredicate(root, builder, user);
	}

	@Override
	public boolean matches(Project project, User user) {
		return new OrCriteriaHelper<Project>(criterias).matches(project, user);
	}

	@Override
	public boolean needsLogin() {
		return new OrCriteriaHelper<Project>(criterias).needsLogin();
	}

	@Override
	public String toString() {
		return new OrCriteriaHelper<Project>(criterias).toString();
	}
	
}
