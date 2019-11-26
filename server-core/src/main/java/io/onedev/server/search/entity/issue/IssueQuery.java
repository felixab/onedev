package io.onedev.server.search.entity.issue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import io.onedev.commons.codeassist.AntlrUtils;
import io.onedev.server.OneDev;
import io.onedev.server.OneException;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.issue.fieldspec.BooleanField;
import io.onedev.server.issue.fieldspec.BuildChoiceField;
import io.onedev.server.issue.fieldspec.ChoiceField;
import io.onedev.server.issue.fieldspec.DateField;
import io.onedev.server.issue.fieldspec.FieldSpec;
import io.onedev.server.issue.fieldspec.GroupChoiceField;
import io.onedev.server.issue.fieldspec.IssueChoiceField;
import io.onedev.server.issue.fieldspec.NumberField;
import io.onedev.server.issue.fieldspec.PullRequestChoiceField;
import io.onedev.server.issue.fieldspec.TextField;
import io.onedev.server.issue.fieldspec.UserChoiceField;
import io.onedev.server.model.Issue;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.search.entity.EntityQuery;
import io.onedev.server.search.entity.EntitySort;
import io.onedev.server.search.entity.EntitySort.Direction;
import io.onedev.server.search.entity.issue.IssueQueryParser.AndCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.CriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.FieldOperatorCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.FieldOperatorValueCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.FixedBetweenCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.NotCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.OperatorCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.OperatorValueCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.OrCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.OrderContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.ParensCriteriaContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.QueryContext;
import io.onedev.server.search.entity.issue.IssueQueryParser.RevisionCriteriaContext;
import io.onedev.server.util.IssueConstants;
import io.onedev.server.util.ValueSetEdit;
import io.onedev.server.web.component.issue.workflowreconcile.UndefinedFieldValue;
import io.onedev.server.web.page.admin.issuesetting.IssueSettingPage;
import io.onedev.server.web.util.WicketUtils;

public class IssueQuery extends EntityQuery<Issue> {

	private static final long serialVersionUID = 1L;

	private final IssueCriteria criteria;
	
	private final List<EntitySort> sorts;
	
	public IssueQuery(@Nullable IssueCriteria criteria, List<EntitySort> sorts) {
		this.criteria = criteria;
		this.sorts = sorts;
	}

	public IssueQuery() {
		this(null, new ArrayList<>());
	}
	
	@Nullable
	public IssueCriteria getCriteria() {
		return criteria;
	}

	public List<EntitySort> getSorts() {
		return sorts;
	}

	public static IssueQuery parse(@Nullable Project project, @Nullable String queryString, boolean validate) {
		if (queryString != null) {
			CharStream is = CharStreams.fromString(queryString); 
			IssueQueryLexer lexer = new IssueQueryLexer(is);
			lexer.removeErrorListeners();
			lexer.addErrorListener(new BaseErrorListener() {

				@Override
				public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
						int charPositionInLine, String msg, RecognitionException e) {
					throw new OneException("Malformed query syntax", e);
				}
				
			});
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			IssueQueryParser parser = new IssueQueryParser(tokens);
			parser.removeErrorListeners();
			parser.setErrorHandler(new BailErrorStrategy());
			QueryContext queryContext;
			try {
				queryContext = parser.query();
			} catch (Exception e) {
				if (e instanceof OneException)
					throw e;
				else
					throw new OneException("Malformed query syntax", e);
			}
			CriteriaContext criteriaContext = queryContext.criteria();
			IssueCriteria issueCriteria;
			if (criteriaContext != null) {
				issueCriteria = new IssueQueryBaseVisitor<IssueCriteria>() {

					private long getValueOrdinal(ChoiceField field, String value) {
						List<String> choices = new ArrayList<>(field.getChoiceProvider().getChoices(true).keySet());
						return choices.indexOf(value);
					}
					
					@Override
					public IssueCriteria visitOperatorCriteria(OperatorCriteriaContext ctx) {
						switch (ctx.operator.getType()) {
						case IssueQueryLexer.SubmittedByMe:
							return new SubmittedByMeCriteria();
						case IssueQueryLexer.FixedInCurrentBuild:
							return new FixedInCurrentBuildCriteria();
						default:
							throw new OneException("Unexpected operator: " + ctx.operator.getText());
						}
					}
					
					@Override
					public IssueCriteria visitFieldOperatorCriteria(FieldOperatorCriteriaContext ctx) {
						String fieldName = getValue(ctx.Quoted().getText());
						int operator = ctx.operator.getType();
						if (validate)
							checkField(fieldName, operator);
						if (fieldName.equals(IssueConstants.FIELD_MILESTONE))
							return new MilestoneCriteria(null);
						else
							return new FieldOperatorCriteria(fieldName, operator);
					}
					
					public IssueCriteria visitOperatorValueCriteria(OperatorValueCriteriaContext ctx) {
						String value = getValue(ctx.Quoted().getText());
						if (ctx.SubmittedBy() != null) 
							return new SubmittedByCriteria(value);
						else if (ctx.FixedInBuild() != null) 
							return new FixedInCriteria(project, value);
						else 
							throw new RuntimeException("Unexpected operator: " + ctx.operator.getText());
					}
					
					@Override
					public IssueCriteria visitFixedBetweenCriteria(FixedBetweenCriteriaContext ctx) {
						RevisionCriteriaContext firstRevision = ctx.revisionCriteria(0);
						int firstType = firstRevision.revisionType.getType();
						String firstValue = getValue(firstRevision.Quoted().getText());
						
						RevisionCriteriaContext secondRevision = ctx.revisionCriteria(1);
						int secondType = secondRevision.revisionType.getType();
						String secondValue = getValue(secondRevision.Quoted().getText());
						
						return new FixedBetweenCriteria(project, firstType, firstValue, secondType, secondValue);
					}
					
					@Override
					public IssueCriteria visitParensCriteria(ParensCriteriaContext ctx) {
						return visit(ctx.criteria());
					}

					@Override
					public IssueCriteria visitFieldOperatorValueCriteria(FieldOperatorValueCriteriaContext ctx) {
						String fieldName = getValue(ctx.Quoted(0).getText());
						String value = getValue(ctx.Quoted(1).getText());
						int operator = ctx.operator.getType();
						if (validate)
							checkField(fieldName, operator);
						
						switch (operator) {
						case IssueQueryLexer.IsBefore:
						case IssueQueryLexer.IsAfter:
							if (fieldName.equals(IssueConstants.FIELD_SUBMIT_DATE)) 
								return new SubmitDateCriteria(value, operator);
							else if (fieldName.equals(IssueConstants.FIELD_UPDATE_DATE))
								return new UpdateDateCriteria(value, operator);
							else 
								return new DateFieldCriteria(fieldName, value, operator);
						case IssueQueryLexer.Contains:
							if (fieldName.equals(IssueConstants.FIELD_TITLE)) {
								return new TitleCriteria(value);
							} else if (fieldName.equals(IssueConstants.FIELD_DESCRIPTION)) {
								return new DescriptionCriteria(value);
							} else if (fieldName.equals(IssueConstants.FIELD_COMMENT)) {
								return new CommentCriteria(value);
							} else {
								FieldSpec fieldSpec = getGlobalIssueSetting().getFieldSpec(fieldName);
								if (fieldSpec instanceof TextField) {
									return new StringFieldCriteria(fieldName, value, operator);
								} else {
									long ordinal;
									if (validate)
										ordinal = getValueOrdinal((ChoiceField) fieldSpec, value);
									else
										ordinal = 0;
									return new ChoiceFieldCriteria(fieldName, value, ordinal, operator, true);
								}
							}
						case IssueQueryLexer.Is:
							if (fieldName.equals(IssueConstants.FIELD_MILESTONE)) {
								return new MilestoneCriteria(value);
							} else if (fieldName.equals(IssueConstants.FIELD_STATE)) {
								return new StateCriteria(value);
							} else if (fieldName.equals(IssueConstants.FIELD_VOTE_COUNT)) {
								return new VoteCountCriteria(getIntValue(value), operator);
							} else if (fieldName.equals(IssueConstants.FIELD_COMMENT_COUNT)) {
								return new CommentCountCriteria(getIntValue(value), operator);
							} else if (fieldName.equals(IssueConstants.FIELD_NUMBER)) {
								return new NumberCriteria(getIntValue(value), operator);
							} else {
								FieldSpec field = getGlobalIssueSetting().getFieldSpec(fieldName);
								if (field instanceof IssueChoiceField) {
									return new IssueFieldCriteria(fieldName, project, value);
								} else if (field instanceof BuildChoiceField) {
									return new BuildFieldCriteria(fieldName, project, value);
								} else if (field instanceof PullRequestChoiceField) {
									return new PullRequestFieldCriteria(fieldName, project, value);
								} else if (field instanceof BooleanField) {
									return new BooleanFieldCriteria(fieldName, getBooleanValue(value));
								} else if (field instanceof NumberField) {
									return new NumericFieldCriteria(fieldName, getIntValue(value), operator);
								} else if (field instanceof ChoiceField) { 
									long ordinal = getValueOrdinal((ChoiceField) field, value);
									return new ChoiceFieldCriteria(fieldName, value, ordinal, operator, false);
								} else if (field instanceof UserChoiceField 
										|| field instanceof GroupChoiceField) {
									return new ChoiceFieldCriteria(fieldName, value, -1, operator, false);
								} else {
									return new StringFieldCriteria(fieldName, value, operator);
								}
							}
						case IssueQueryLexer.IsLessThan:
						case IssueQueryLexer.IsGreaterThan:
							if (fieldName.equals(IssueConstants.FIELD_VOTE_COUNT)) {
								return new VoteCountCriteria(getIntValue(value), operator);
							} else if (fieldName.equals(IssueConstants.FIELD_COMMENT_COUNT)) {
								return new CommentCountCriteria(getIntValue(value), operator);
							} else if (fieldName.equals(IssueConstants.FIELD_NUMBER)) {
								return new NumberCriteria(getIntValue(value), operator);
							} else {
								FieldSpec field = getGlobalIssueSetting().getFieldSpec(fieldName);
								if (field instanceof NumberField) {
									return new NumericFieldCriteria(fieldName, getIntValue(value), operator);
								} else {
									long ordinal;
									if (validate)
										ordinal = getValueOrdinal((ChoiceField) field, value);
									else
										ordinal = 0;
									return new ChoiceFieldCriteria(fieldName, value, ordinal, operator, false);
								}
							}
						default:
							throw new OneException("Unexpected operator " + getRuleName(operator));
						}
					}
					
					@Override
					public IssueCriteria visitOrCriteria(OrCriteriaContext ctx) {
						List<IssueCriteria> childCriterias = new ArrayList<>();
						for (CriteriaContext childCtx: ctx.criteria())
							childCriterias.add(visit(childCtx));
						return new OrCriteria(childCriterias);
					}

					@Override
					public IssueCriteria visitAndCriteria(AndCriteriaContext ctx) {
						List<IssueCriteria> childCriterias = new ArrayList<>();
						for (CriteriaContext childCtx: ctx.criteria())
							childCriterias.add(visit(childCtx));
						return new AndCriteria(childCriterias);
					}

					@Override
					public IssueCriteria visitNotCriteria(NotCriteriaContext ctx) {
						return new NotCriteria(visit(ctx.criteria()));
					}
					
				}.visit(criteriaContext);
			} else {
				issueCriteria = null;
			}

			List<EntitySort> issueSorts = new ArrayList<>();
			for (OrderContext order: queryContext.order()) {
				String fieldName = getValue(order.Quoted().getText());
				if (validate && !IssueConstants.ORDER_FIELDS.containsKey(fieldName)) {
					FieldSpec fieldSpec = getGlobalIssueSetting().getFieldSpec(fieldName);
					if (!(fieldSpec instanceof ChoiceField) && !(fieldSpec instanceof DateField) 
							&& !(fieldSpec instanceof NumberField)) {
						throw new OneException("Can not order by field: " + fieldName);
					}
				}
				
				EntitySort issueSort = new EntitySort();
				issueSort.setField(fieldName);
				if (order.direction != null && order.direction.getText().equals("asc"))
					issueSort.setDirection(Direction.ASCENDING);
				else
					issueSort.setDirection(Direction.DESCENDING);
				issueSorts.add(issueSort);
			}
			
			return new IssueQuery(issueCriteria, issueSorts);
		} else {
			return new IssueQuery();
		}
	}
	
	private static GlobalIssueSetting getGlobalIssueSetting() {
		if (WicketUtils.getPage() instanceof IssueSettingPage) 
			return ((IssueSettingPage)WicketUtils.getPage()).getSetting();
		else
			return OneDev.getInstance(SettingManager.class).getIssueSetting();
	}
	
	private static OneException newOperatorException(String fieldName, int operator) {
		return new OneException("Field '" + fieldName + "' is not applicable for operator '" + getRuleName(operator) + "'");
	}
	
	public static void checkField(String fieldName, int operator) {
		FieldSpec fieldSpec = getGlobalIssueSetting().getFieldSpec(fieldName);
		if (fieldSpec == null && !IssueConstants.QUERY_FIELDS.contains(fieldName))
			throw new OneException("Field not found: " + fieldName);
		switch (operator) {
		case IssueQueryLexer.IsEmpty:
			if (IssueConstants.QUERY_FIELDS.contains(fieldName) 
					&& !fieldName.equals(IssueConstants.FIELD_MILESTONE)) { 
				throw newOperatorException(fieldName, operator);
			}
			break;
		case IssueQueryLexer.IsMe:
			if (!(fieldSpec instanceof UserChoiceField))
				throw newOperatorException(fieldName, operator);
			break;
		case IssueQueryLexer.IsCurrent:
		case IssueQueryLexer.IsPrevious:
			if (!(fieldSpec instanceof BuildChoiceField))
				throw newOperatorException(fieldName, operator);
			break;
		case IssueQueryLexer.IsBefore:
		case IssueQueryLexer.IsAfter:
			if (!fieldName.equals(IssueConstants.FIELD_SUBMIT_DATE) 
					&& !fieldName.equals(IssueConstants.FIELD_UPDATE_DATE) 
					&& !(fieldSpec instanceof DateField)) {
				throw newOperatorException(fieldName, operator);
			}
			break;
		case IssueQueryLexer.Contains:
			if (!fieldName.equals(IssueConstants.FIELD_TITLE) 
					&& !fieldName.equals(IssueConstants.FIELD_DESCRIPTION)
					&& !fieldName.equals(IssueConstants.FIELD_COMMENT)
					&& !(fieldSpec instanceof TextField) 
					&& !(fieldSpec != null && fieldSpec.isAllowMultiple())) {
				throw newOperatorException(fieldName, operator);
			}
			break;
		case IssueQueryLexer.Is:
			if (!fieldName.equals(IssueConstants.FIELD_STATE) 
					&& !fieldName.equals(IssueConstants.FIELD_VOTE_COUNT) 
					&& !fieldName.equals(IssueConstants.FIELD_COMMENT_COUNT) 
					&& !fieldName.equals(IssueConstants.FIELD_NUMBER)
					&& !fieldName.equals(IssueConstants.FIELD_MILESTONE)
					&& !(fieldSpec instanceof IssueChoiceField)
					&& !(fieldSpec instanceof PullRequestChoiceField)
					&& !(fieldSpec instanceof BuildChoiceField)
					&& !(fieldSpec instanceof BooleanField)
					&& !(fieldSpec instanceof NumberField) 
					&& !(fieldSpec instanceof ChoiceField) 
					&& !(fieldSpec instanceof UserChoiceField)
					&& !(fieldSpec instanceof GroupChoiceField)
					&& !(fieldSpec instanceof TextField)) {
				throw newOperatorException(fieldName, operator);
			}
			break;
		case IssueQueryLexer.IsLessThan:
		case IssueQueryLexer.IsGreaterThan:
			if (!fieldName.equals(IssueConstants.FIELD_VOTE_COUNT)
					&& !fieldName.equals(IssueConstants.FIELD_COMMENT_COUNT)
					&& !fieldName.equals(IssueConstants.FIELD_NUMBER)
					&& !(fieldSpec instanceof NumberField) 
					&& !(fieldSpec instanceof ChoiceField))
				throw newOperatorException(fieldName, operator);
			break;
		}
	}
	
	@Override
	public boolean needsLogin() {
		return criteria != null && criteria.needsLogin();
	}
	
	@Override
	public boolean matches(Issue issue, User user) {
		return criteria == null || criteria.matches(issue, user);
	}
	
	public static String getRuleName(int rule) {
		return AntlrUtils.getLexerRuleName(IssueQueryLexer.ruleNames, rule);
	}
	
	public static int getOperator(String operatorName) {
		return AntlrUtils.getLexerRule(IssueQueryLexer.ruleNames, operatorName);
	}
	
	public Collection<String> getUndefinedStates() {
		if (criteria != null) 
			return criteria.getUndefinedStates();
		else
			return new ArrayList<>();
	}
	
	public void onRenameState(String oldName, String newName) {
		if (criteria != null)
			criteria.onRenameState(oldName, newName);
	}

	public boolean onDeleteState(String stateName) {
		return criteria != null && criteria.onDeleteState(stateName);
	}
	
	public Collection<String> getUndefinedFields() {
		Set<String> undefinedFields = new HashSet<>();
		if (criteria != null)
			undefinedFields.addAll(criteria.getUndefinedFields());
		for (EntitySort sort: sorts) {
			if (!IssueConstants.QUERY_FIELDS.contains(sort.getField()) 
					&& getGlobalIssueSetting().getFieldSpec(sort.getField()) == null) {
				undefinedFields.add(sort.getField());
			}
		}
		return undefinedFields;
	}

	public void onRenameField(String oldName, String newName) {
		if (criteria != null)
			criteria.onRenameField(oldName, newName);
		for (EntitySort sort: sorts) {
			if (sort.getField().equals(oldName))
				sort.setField(newName);
		}
	}

	public boolean onDeleteField(String fieldName) {
		if (criteria != null && criteria.onDeleteField(fieldName))
			return true;
		for (Iterator<EntitySort> it = sorts.iterator(); it.hasNext();) {
			if (it.next().getField().equals(fieldName))
				it.remove();
		}
		return false;
	}
	
	public Collection<UndefinedFieldValue> getUndefinedFieldValues() {
		if (criteria != null)
			return criteria.getUndefinedFieldValues();
		else
			return new HashSet<>();
	}

	public boolean onEditFieldValues(String fieldName, ValueSetEdit valueSetEdit) {
		return criteria != null && criteria.onEditFieldValues(fieldName, valueSetEdit);
	}
	
	public boolean fixUndefinedFieldValues(Map<String, ValueSetEdit> valueSetEdits) {
		for (Map.Entry<String, ValueSetEdit> entry: valueSetEdits.entrySet()) {
			if (onEditFieldValues(entry.getKey(), entry.getValue()))
				return true;
		}
		return false;
	}
	
	public static IssueQuery merge(IssueQuery query1, IssueQuery query2) {
		List<IssueCriteria> criterias = new ArrayList<>();
		if (query1.getCriteria() != null)
			criterias.add(query1.getCriteria());
		if (query2.getCriteria() != null)
			criterias.add(query2.getCriteria());
		List<EntitySort> sorts = new ArrayList<>();
		sorts.addAll(query1.getSorts());
		sorts.addAll(query2.getSorts());
		return new IssueQuery(IssueCriteria.of(criterias), sorts);
	}

}
