package com.yukthi.persistence.repository.executors;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yukthi.persistence.EntityDetails;
import com.yukthi.persistence.ExtendedTableDetails;
import com.yukthi.persistence.ExtendedTableEntity;
import com.yukthi.persistence.FieldDetails;
import com.yukthi.persistence.ICrudRepository;
import com.yukthi.persistence.IDataStore;
import com.yukthi.persistence.ITransaction;
import com.yukthi.persistence.conversion.ConversionService;
import com.yukthi.persistence.listeners.EntityEventType;
import com.yukthi.persistence.query.ColumnParam;
import com.yukthi.persistence.query.QueryCondition;
import com.yukthi.persistence.query.UpdateColumnParam;
import com.yukthi.persistence.query.UpdateQuery;
import com.yukthi.persistence.repository.InvalidRepositoryException;
import com.yukthi.persistence.repository.annotations.Field;
import com.yukthi.persistence.repository.annotations.JoinOperator;
import com.yukthi.persistence.repository.annotations.Operator;
import com.yukthi.persistence.repository.annotations.UpdateFunction;
import com.yukthi.persistence.repository.annotations.UpdateOperator;
import com.yukthi.utils.exceptions.InvalidStateException;

@QueryExecutorPattern(prefixes = {"update"}, annotatedWith = UpdateFunction.class)
public class UpdateQueryExecutor extends AbstractPersistQueryExecutor
{
	private static Logger logger = LogManager.getLogger(UpdateQueryExecutor.class);

	private Class<?> returnType;
	private ReentrantLock queryLock = new ReentrantLock();
	private boolean entityUpdate = false;
	private ConditionQueryBuilder conditionQueryBuilder;
	private String methodDesc;
	
	private UpdateQuery updateQuery;
	
	public UpdateQueryExecutor(Class<?> repositoryType, Method method, EntityDetails entityDetails)
	{
		super.entityDetails = entityDetails;
		super.repositoryType = repositoryType;
		
		conditionQueryBuilder = new ConditionQueryBuilder(entityDetails);
		methodDesc = String.format("update method '%s' of repository - '%s'", method.getName(), repositoryType.getName());
		
		
		Class<?> paramTypes[] = method.getParameterTypes();

		if(paramTypes == null || paramTypes.length == 0)
		{
			throw new InvalidRepositoryException("Zero parameter update method '" + method.getName() + "' in repository: " + repositoryType.getName());
		}
		
		boolean isCoreInterface = ICrudRepository.class.equals(method.getDeclaringClass());
		Class<?> firstParamType = TypeUtils.getRawType(method.getGenericParameterTypes()[0], repositoryType);
		
		if( ( paramTypes.length >= 1 && entityDetails.getEntityType().equals(firstParamType) ) || isCoreInterface)
		{
			entityUpdate = true;
			
			super.fetchConditonsByAnnotations(method, false, conditionQueryBuilder, methodDesc, false);
			super.fetchMethodLevelConditions(method, conditionQueryBuilder, methodDesc);
		}
		else
		{
			updateQuery = new UpdateQuery(entityDetails);
			
			if(!super.fetchConditonsByAnnotations(method, false, conditionQueryBuilder, methodDesc, false))
			{
				throw new InvalidRepositoryException("For non-entity update method '" + method.getName() + "' no conditions are specified, in repository: " + repositoryType.getName());
			}
			
			super.fetchMethodLevelConditions(method, conditionQueryBuilder, methodDesc);
			
			if(!fetchColumnsByAnnotations(method))
			{
				throw new InvalidRepositoryException("For non-entity update method '" + method.getName() + "' no columns are specified, in repository: " + repositoryType.getName());
			}
		}
		
		returnType = method.getReturnType();
		
		if(!boolean.class.equals(returnType) && !void.class.equals(returnType) && !int.class.equals(returnType))
		{
			throw new InvalidRepositoryException("Update method '" + method.getName() + "' found with non-boolean, non-void and non-int return type in repository: " + repositoryType.getName());
		}
	}
	
	private boolean fetchColumnsByAnnotations(Method method)
	{
		logger.trace("Started method: fetchColumnsByAnnotations");
		
		Parameter paramters[] = method.getParameters();
		
		Field field = null;
		boolean found = false;
		FieldDetails fieldDetails = null;
		
		//fetch conditions for each argument
		for(int i = 0; i < paramters.length; i++)
		{
			field = paramters[i].getAnnotation(Field.class);
			
			if(field == null)
			{
				continue;
			}
			
			fieldDetails = this.entityDetails.getFieldDetailsByField(field.value());
			
			if(fieldDetails == null)
			{
				throw new InvalidRepositoryException("@Field with invalid name '" + field.value() + "' is specified for update method '" 
						+ method.getName() + "' of repository: " + repositoryType.getName());
			}
			
			//if version field is specified explicitly for update
			if(fieldDetails.isVersionField())
			{
				throw new InvalidRepositoryException("Version field '{}' is configured for explicit update in repository method {}.{}", 
						field.value(), repositoryType.getName(), method.getName());
			}
			
			updateQuery.addColumn(new UpdateColumnParam(fieldDetails.getDbColumnName(), null, i, field.updateOp()));
			found = true;
		}
		
		//add implicit version update instructions
		if(entityDetails.hasVersionField())
		{
			updateQuery.addColumn(new UpdateColumnParam(entityDetails.getVersionField().getDbColumnName(), 1, -1, UpdateOperator.ADD));
		}

		return found;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void updateExtensionFields(IDataStore dataStore, ConversionService conversionService, Object entity)
	{
		ExtendedTableDetails extendedTableDetails = entityDetails.getExtendedTableDetails();
		
		if(extendedTableDetails == null)
		{
			return;
		}
		
		java.lang.reflect.Field extDataHolder = extendedTableDetails.getEntityField();
		
		Map<String, Object> extendedValues = null;
		
		try
		{
			extDataHolder.setAccessible(true);
			extendedValues = (Map)extDataHolder.get(entity);
		}catch(Exception ex)
		{
			throw new InvalidStateException(ex, "An error occurred while fetching extended values");
		}
		
		if(extendedValues == null || extendedValues.isEmpty())
		{
			return;
		}
		
		Object id = entityDetails.getIdField().getValue(entity);
		
		UpdateQuery updateQuery = new UpdateQuery(extendedTableDetails.toEntityDetails(entityDetails));
		
		for(String field : extendedValues.keySet())
		{
			updateQuery.addColumn(new UpdateColumnParam(field.toLowerCase(), extendedValues.get(field), -1, UpdateOperator.NONE));
		}
		
		updateQuery.addCondition(new QueryCondition(null, ExtendedTableEntity.COLUMN_ENTITY_ID, Operator.EQ, id, null, false));
		
		if( dataStore.update(updateQuery, extendedTableDetails.toEntityDetails(entityDetails)) <= 0)
		{
			throw new InvalidStateException("An error occurred while updating extension fields");
		}
	}
	
	private Object updateFullEntity(QueryExecutionContext context, IDataStore dataStore, ConversionService conversionService, Object... params)
	{
		logger.trace("Started method: updateFullEntity");
		
		Object entity = params[0];
		
		if(entity == null)
		{
			throw new NullPointerException("Entity can not be null");
		}
		
		if(dataStore.isExplicitUniqueCheckRequired())
		{
			//check if unique constraints are getting violated
			checkForUniqueConstraints(dataStore, conversionService, entity, true);//TODO: Read only fields should be skipped
		}
		
		if(dataStore.isExplicitForeignCheckRequired())
		{
			//check if all foreign parent keys are available
			checkForForeignConstraints(dataStore, conversionService, entity);
		}

		UpdateQuery query = new UpdateQuery(entityDetails);
		Object value = null;
		
		for(FieldDetails field: entityDetails.getFieldDetails())
		{
			if(field.isIdField())
			{
				continue;
			}
			
			//if version field, add instruction to increment it
			if(field.isVersionField())
			{
				query.addColumn(new UpdateColumnParam(field.getDbColumnName(), 1, -1, UpdateOperator.ADD));
				continue;
			}
			
			//if field is not updateable skip the field
			if(!field.isUpdateable())
			{
				continue;
			}
			
			value = field.getValue(entity);
			
			//if current field is relation field
			if(field.isRelationField())
			{
				//if current table does not own relation, ignore current field
				if(!field.isTableOwned())
				{
					//TODO: Take care of cases where join table is involved
					continue;
				}
				
				if(value != null)
				{
					//if current table owns the relation in same table, replace the entity valu with foreign entity id value
					value = field.getForeignConstraintDetails().getTargetEntityDetails().getIdField().getValue(value);
				}
			}
			
			value = conversionService.convertToDBType(value, field);
			
			query.addColumn(new UpdateColumnParam(field.getDbColumnName(), value, -1, UpdateOperator.NONE));
		}
		
		query.addCondition(new QueryCondition(null, entityDetails.getIdField().getDbColumnName(), Operator.EQ, entityDetails.getIdField().getValue(entity), JoinOperator.AND, false));

		//if version field is defined on the entity add it to the condition
		if(entityDetails.hasVersionField())
		{
			query.addCondition(new QueryCondition(null, entityDetails.getVersionField().getDbColumnName(), Operator.EQ, entityDetails.getVersionField().getValue(entity), JoinOperator.AND, false));
		}
		
		conditionQueryBuilder.loadConditionalQuery(context.getRepositoryExecutionContext(), query, params);
		
		try(ITransaction transaction = dataStore.getTransactionManager().newOrExistingTransaction())
		{
			super.notifyEntityEvent(null, entity, EntityEventType.PRE_UPDATE);
			
			int res = dataStore.update(query, entityDetails);
			
			updateExtensionFields(dataStore, conversionService, entity);
			
			if(res > 0)
			{
				super.notifyEntityEvent(null, entity, EntityEventType.POST_UPDATE);
			}
			
			transaction.commit();

			if(boolean.class.equals(returnType))
			{
				return (res > 0);
			}
			
			return (int.class.equals(returnType)) ? res : null;
		}catch(Exception ex)
		{
			//rethrow the catched exception
			if(ex instanceof RuntimeException)
			{
				throw (RuntimeException)ex;
			}
			
			throw new IllegalStateException(ex);
		}
	}

	
	/* (non-Javadoc)
	 * @see com.yukthi.persistence.repository.executors.QueryExecutor#execute(com.yukthi.persistence.repository.executors.QueryExecutionContext, com.yukthi.persistence.IDataStore, com.yukthi.persistence.conversion.ConversionService, java.lang.Object[])
	 */
	@Override
	public Object execute(QueryExecutionContext context, IDataStore dataStore, ConversionService conversionService, Object... params)
	{
		logger.trace("Started method: execute");
		
		if(entityUpdate)
		{
			return updateFullEntity(context, dataStore, conversionService, params);
		}
		
		queryLock.lock();
		
		try
		{
			Object value = null;
			
			updateQuery.clearConditions();
			conditionQueryBuilder.loadConditionalQuery(context.getRepositoryExecutionContext(), updateQuery, params);
			
			//TODO: When unique fields are getting updated, make sure unique constraints are not violated
				//during unique field update might be we have to mandate id is provided as condition
			
			
			//TODO: Extension field update using annotaionts
			FieldDetails field = null;
			
			for(ColumnParam column: updateQuery.getColumns())
			{
				field = entityDetails.getFieldDetailsByColumn(column.getName());
				
				//index would be less than internal fields like version
				if(column.getIndex() < 0)
				{
					continue; 
				}
				
				value = params[column.getIndex()];
				
				//if current field is relation field
				if(field.isRelationField())
				{
					//if current table does not own relation, ignore current field
					if(!field.isTableOwned())
					{
						//TODO: Take care of cases where join table is involved
						continue;
					}
					
					if(value != null)
					{
						//if current table owns the relation in same table, replace the entity value with foreign entity id value
						value = field.getForeignConstraintDetails().getTargetEntityDetails().getIdField().getValue(value);
					}
				}

				value = conversionService.convertToDBType(value, field);

				column.setValue(value);
			}
			
			try(ITransaction transaction = dataStore.getTransactionManager().newOrExistingTransaction())
			{
				int res = dataStore.update(updateQuery, entityDetails);

				transaction.commit();
				
				if(int.class.equals(returnType))
				{
					return res;
				}
				
				return (boolean.class.equals(returnType)) ? (res > 0) : null;
			}catch(Exception ex)
			{
				//rethrow the catched exception
				if(ex instanceof RuntimeException)
				{
					throw (RuntimeException)ex;
				}
				
				throw new IllegalStateException(ex);
			}
		}finally
		{
			queryLock.unlock();
		}
		
	}
}
