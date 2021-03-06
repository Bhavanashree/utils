package com.yukthi.persistence.rdbms;

import java.io.ByteArrayOutputStream;
import java.io.CharArrayWriter;
import java.io.Closeable;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yukthi.ccg.xml.XMLBeanParser;
import com.yukthi.persistence.EntityDetails;
import com.yukthi.persistence.EntityDetailsFactory;
import com.yukthi.persistence.IDataStore;
import com.yukthi.persistence.IFinderRecordProcessor;
import com.yukthi.persistence.IFinderRecordProcessor.Action;
import com.yukthi.persistence.ITransaction;
import com.yukthi.persistence.ITransactionManager;
import com.yukthi.persistence.LobData;
import com.yukthi.persistence.NativeQueryFactory;
import com.yukthi.persistence.PersistenceException;
import com.yukthi.persistence.Record;
import com.yukthi.persistence.TransactionWrapper;
import com.yukthi.persistence.UnsupportedOperationException;
import com.yukthi.persistence.conversion.ConversionService;
import com.yukthi.persistence.query.ChildrenExistenceQuery;
import com.yukthi.persistence.query.ColumnParam;
import com.yukthi.persistence.query.CountQuery;
import com.yukthi.persistence.query.CreateExtendedTableQuery;
import com.yukthi.persistence.query.CreateIndexQuery;
import com.yukthi.persistence.query.CreateTableQuery;
import com.yukthi.persistence.query.DeleteQuery;
import com.yukthi.persistence.query.DropTableQuery;
import com.yukthi.persistence.query.FetchChildrenIdsQuery;
import com.yukthi.persistence.query.FinderQuery;
import com.yukthi.persistence.query.QueryCondition;
import com.yukthi.persistence.query.SaveQuery;
import com.yukthi.persistence.query.UpdateQuery;
import com.yukthi.persistence.rdbms.converters.BlobConverter;
import com.yukthi.persistence.rdbms.converters.ClobConverter;
import com.yukthi.utils.ObjectWrapper;

public class RdbmsDataStore implements IDataStore
{
	public static final String TEMPLATE_NAME_MYSQL = "mysql";
	public static final String TEMPLATE_NAME_DERBY = "derby";
	
	private static Logger logger = LogManager.getLogger(RdbmsDataStore.class);
	
	private RdbmsConfiguration rdbmsConfig;
	private ConversionService conversionService = new ConversionService();
	private RdbmsTransactionManager transactionManager = new RdbmsTransactionManager();
	
	private EntityDetailsFactory entityDetailsFactory;
	
	private NativeQueryFactory nativeQueryFactory;
	
	private String templatesName;
	
	public RdbmsDataStore(String templatesName)
	{
		rdbmsConfig = new RdbmsConfiguration();
		this.templatesName = templatesName;
		
		try
		{
			logger.debug("Using RDBMS template type: " + templatesName);
			
			XMLBeanParser.parse(RdbmsDataStore.class.getResourceAsStream("/" + templatesName + ".xml"), rdbmsConfig);
		}catch(RuntimeException ex)
		{
			logger.error("An error occurred while loading template: " + templatesName, ex);
			throw ex;
		}
		
		//add blob and clob converters as default converters
		conversionService.addConverter(new BlobConverter());
		conversionService.addConverter(new ClobConverter());
	}
	
	/* (non-Javadoc)
	 * @see com.yukthi.persistence.IDataStore#isPagingSupported()
	 */
	@Override
	public boolean isPagingSupported()
	{
		return rdbmsConfig.isPagingSupported();
	}
	
	/* (non-Javadoc)
	 * @see com.yukthi.persistence.IDataStore#setNativeQueryFactory(com.yukthi.persistence.NativeQueryFactory)
	 */
	@Override
	public void setNativeQueryFactory(NativeQueryFactory factory)
	{
		this.nativeQueryFactory = factory;
	}
	
	public void setEntityDetailsFactory(EntityDetailsFactory entityDetailsFactory)
	{
		this.entityDetailsFactory = entityDetailsFactory;
	}
	
	public void setConversionService(ConversionService conversionService)
	{
		this.conversionService = conversionService;
	}

	@Override
	public ConversionService getConversionService()
	{
		return conversionService;
	}
	
	public void setDataSource(DataSource dataSource)
	{
		transactionManager.setDataSource(dataSource);
	}
	
	@Override
	public ITransactionManager<? extends ITransaction> getTransactionManager()
	{
		return transactionManager;
	}
	
	private void closeResources(ResultSet rs, Statement statement)
	{
		try
		{
			if(rs != null)
			{
				rs.close();
			}
			
			if(statement != null)
			{
				statement.close();
			}
		}catch(Exception ex)
		{
			logger.error("An error occurred while closing DB resources", ex);
		}
	}

	@Override
	public Set<String> getColumnNames(String tableName)
	{
		logger.trace("Started method: getColumnNames");
		logger.trace("Fetching columns for table {}", tableName);
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();
			
			ResultSet rs = connection.getMetaData().getColumns(null, null, tableName, null);
			Set<String> columns = new HashSet<>();
			
			while(rs.next())
			{
				columns.add(rs.getString("COLUMN_NAME"));
			}
			
			rs.close();
		
			logger.debug("For table '{}' found columns as - {}", tableName, columns);
			
			if(columns.isEmpty())
			{
				throw new IllegalStateException("No table found with name '" + tableName + "' or found with zero columns");
			}
			
			transaction.commit();
			return columns;
		}catch(Exception ex)
		{
			logger.info("An error occurred while fetching column names of table: " + tableName, ex);
			throw new PersistenceException("An error occurred while fetching column names of table: " + tableName, ex);
		}
	}

	@Override
	public void checkAndCreateSequence(String name)
	{
		logger.trace("Started method: checkAndCreateSequence");
		
		if(!rdbmsConfig.hasQuery(RdbmsConfiguration.CREATE_SEQUENCE_QUERY) || !rdbmsConfig.hasQuery(RdbmsConfiguration.CHECK_SEQUENCE_QUERY))
		{
			throw new UnsupportedOperationException("Create sequence is not supported by this data-store: " + templatesName);
		}
		
		Statement statement = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();

			statement = connection.createStatement();
			
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.CHECK_SEQUENCE_QUERY, "name", name);
			logger.debug("Built check sequence query as:\n\t {}", query);
			
			rs = statement.executeQuery(query);

			//if any row is returned by CHECK_SEQUENCE_QUERY, assume sequence already exists
			if(rs.next())
			{
				logger.debug("Found sequence '" + name + "' to be already existing one.");
				return;
			}
			
			logger.debug("Found sequence '" + name + "' does not exits. Creating new sequence");
			
			query = rdbmsConfig.buildQuery(RdbmsConfiguration.CREATE_SEQUENCE_QUERY, "name", name);
			logger.debug("Built create sequence query as:\n\t {}", query);			
			
			statement.execute(query);
			
			transaction.commit();
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing create-sequence-query", ex);
			throw new PersistenceException("An error occurred while executing create-sequence-query", ex);
		}finally
		{
			closeResources(rs, statement);
		}
	}

	@Override
	public void createTable(CreateTableQuery createQuery)
	{
		logger.trace("Started method: createTable");
		
		Statement statement = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();

			statement = connection.createStatement();
			
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.CREATE_TABLE, "query", createQuery);
			
			logger.debug("Built create-table query as: \n\t{}", query);
			
			statement.execute(query);
			transaction.commit();
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing create-table-query", ex);
			throw new PersistenceException("An error occurred while executing create-table-query", ex);
		}finally
		{
			closeResources(null, statement);
		}
	}

	@Override
	public void createExtendedTable(CreateExtendedTableQuery createExtendedTableQuery)
	{
		logger.trace("Started method: createExtendedTable");
		
		Statement statement = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();

			statement = connection.createStatement();
			
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.CREATE_EXTENDED_TABLE, "query", createExtendedTableQuery);
			
			logger.debug("Built create-extended-table query as: \n\t{}", query);
			
			statement.execute(query);
			transaction.commit();
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing create-extended-table-query", ex);
			throw new PersistenceException("An error occurred while executing create-extended-table-query", ex);
		}finally
		{
			closeResources(null, statement);
		}
	}

	@Override
	public void createIndex(CreateIndexQuery creatIndexQuery)
	{
		logger.trace("Started method: createIndex");
		
		Statement statement = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();

			statement = connection.createStatement();
			
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.CREATE_INDEX, "query", creatIndexQuery);
			
			logger.debug("Built create-index query as: \n\t{}", query);
			
			statement.execute(query);
			transaction.commit();
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing create-index-query", ex);
			throw new PersistenceException("An error occurred while executing create-index-query", ex);
		}finally
		{
			closeResources(null, statement);
		}
	}

	@Override
	public long getCount(CountQuery countQuery, EntityDetails entityDetails)
	{
		logger.trace("Started method: getCount");
		
		List<QueryCondition> conditions = countQuery.getConditions();
		
		/*
		if(conditions == null || conditions.isEmpty())
		{
			throw new IllegalStateException("Existence query is requested without conditions: " + existenceQuery);
		}
		*/
		
		logger.debug("Fetching count of records from table '{}' using query: {}", countQuery.getTableName(), countQuery);
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.COUNT_QUERY, "query", countQuery);
			
			logger.debug("Built existence query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			
			if(conditions != null)
			{
				for(QueryCondition condition: conditions)
				{
					pstmt.setObject(index, condition.getValue());
					params.add(condition.getValue());
					
					index++;
				}
			}
			
			logger.debug("Executing using params: {}", params);
			
			rs = pstmt.executeQuery();
			
			if(!rs.next())
			{
				transaction.commit();
				return 0;
			}
			
			int count = rs.getInt(1);
			
			logger.debug("Existence of {} records found from table: {}", count, countQuery.getTableName());
			
			transaction.commit();
			return count;
		}catch(Exception ex)
		{
			logger.error("An error occurred while checking rows existence from table '" 
					+ countQuery.getTableName() + "' using query: " + countQuery, ex);
			throw new PersistenceException("An error occurred while checking rows existence from table '" 
						+ countQuery.getTableName() + "' using query: " + countQuery, ex);
		}finally
		{
			closeResources(rs, pstmt);
		}
	}
	
	@Override
	public int checkChildrenExistence(ChildrenExistenceQuery childrenExistenceQuery)
	{
		logger.trace("Started method: checkChildrenExistence");
		logger.debug("Checking children records from table '{}' using query: {}", childrenExistenceQuery.getChildTableName(), childrenExistenceQuery);
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.CHILDREN_EXISTENCE_QUERY, "query", childrenExistenceQuery);
			
			logger.debug("Built children-existence query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			
			if(childrenExistenceQuery.getParentConditions() != null)
			{
				for(QueryCondition condition: childrenExistenceQuery.getParentConditions())
				{
					pstmt.setObject(index, condition.getValue());
					params.add(condition.getValue());
					
					index++;
				}
			}

			if(childrenExistenceQuery.getChildConditions() != null)
			{
				for(QueryCondition condition: childrenExistenceQuery.getChildConditions())
				{
					pstmt.setObject(index, condition.getValue());
					params.add(condition.getValue());
					
					index++;
				}
			}

			logger.debug("Executing using params: " + params);
			
			rs = pstmt.executeQuery();
			
			if(!rs.next())
			{
				return 0;
			}
			
			int res = rs.getInt(1);
			
			logger.debug("Found {} child record(s)", res);
			
			transaction.commit();
			return res;
		}catch(Exception ex)
		{
			logger.error("An error occurred while checking child rows existence from table '" 
					+ childrenExistenceQuery.getTableName() + "' using query: " + childrenExistenceQuery, ex);
			throw new PersistenceException("An error occurred while checking child rows existence from table '" 
						+ childrenExistenceQuery.getTableName() + "' using query: " + childrenExistenceQuery, ex);
		}finally
		{
			closeResources(rs, pstmt);
		}
	}
	
	@Override
	public List<Object> fetchChildrenIds(FetchChildrenIdsQuery fetchChildrenIdsQuery)
	{
		logger.trace("Started method: fetchChildrenIds");
		logger.debug("Fetching children records from table '{}' using query: {}", fetchChildrenIdsQuery.getChildTableName(), fetchChildrenIdsQuery);
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.FETCH_CHILDREN_IDS_QUERY, "query", fetchChildrenIdsQuery);
			
			logger.debug("Built children-fetch query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			
			if(fetchChildrenIdsQuery.getParentConditions() != null)
			{
				for(QueryCondition condition: fetchChildrenIdsQuery.getParentConditions())
				{
					pstmt.setObject(index, condition.getValue());
					params.add(condition.getValue());
					
					index++;
				}
			}

			if(fetchChildrenIdsQuery.getChildConditions() != null)
			{
				for(QueryCondition condition: fetchChildrenIdsQuery.getChildConditions())
				{
					pstmt.setObject(index, condition.getValue());
					params.add(condition.getValue());
					
					index++;
				}
			}

			logger.debug("Executing using params: " + params);
			
			rs = pstmt.executeQuery();
			
			List<Object> ids = new LinkedList<Object>();
			
			while(rs.next())
			{
				ids.add(rs.getObject(1));
			}
			
			logger.debug("Found {} child record(s)", ids.size());
			
			transaction.commit();
			return ids;
		}catch(Exception ex)
		{
			logger.error("An error occurred while fetching child rows existence from table '" 
					+ fetchChildrenIdsQuery.getTableName() + "' using query: " + fetchChildrenIdsQuery, ex);
			throw new PersistenceException("An error occurred while fetchin child rows existence from table '" 
						+ fetchChildrenIdsQuery.getTableName() + "' using query: " + fetchChildrenIdsQuery, ex);
		}finally
		{
			closeResources(rs, pstmt);
		}
	}

	@Override
	public int save(SaveQuery saveQuery, EntityDetails entityDetails, ObjectWrapper<Object> idGenerated)
	{
		logger.trace("Started method: save");
		logger.debug("Trying to save entity to table '{}' using query: {}", saveQuery.getTableName(), saveQuery);
		
		PreparedStatement pstmt = null;
		ResultSet keysRs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.SAVE_QUERY, "query", saveQuery);
			
			logger.debug("Built save query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			Object value = null;
			List<Closeable> closeables = new ArrayList<>();
			
			for(ColumnParam column: saveQuery.getColumns())
			{
				if(column.isSequenceGenerated())
				{
					continue;
				}
				
				value = column.getValue();
				
				if(value instanceof LobData)
				{
					LobData lobData = (LobData)value;
					closeables.add(lobData);
					
					if(lobData.isTextStream())
					{
						pstmt.setCharacterStream(index, lobData.openReader() );
					}
					else
					{
						pstmt.setBinaryStream(index,  lobData.openStream() );
					}
				}
				else
				{
					pstmt.setObject(index, value);
				}
				
				params.add(value);
				index++;
			}
			
			logger.debug("Executing using params: {}", params);
			
			int count = pstmt.executeUpdate();
			
			//close any open closeables (like blob streams)
			for(Closeable closeable : closeables)
			{
				closeable.close();
			}
			
			//if the save was successful
			if(count > 0)
			{
				//try to fetch generated ids
				keysRs = pstmt.getGeneratedKeys();
				
				//if keys are found to be generated
				if(keysRs != null && keysRs.next())
				{
					idGenerated.setValue(keysRs.getObject(1));
				}
				else
				{
					logger.debug("No keys are generated as part of the last statement");
				}
			}
			
			logger.debug("Saved {} records into table: {}", count, saveQuery.getTableName());
			
			transaction.commit();
			return count;
		}catch(Exception ex)
		{
			logger.error("An error occurred while saving entity to table '{}' using query: {}", saveQuery.getTableName(), saveQuery, ex);

			SqlExceptionHandler.handleException("An error occurred while saving entity to table '" 
					+ saveQuery.getTableName() + "'", ex, entityDetailsFactory, false);
			return -1;
		}finally
		{
			closeResources(keysRs, pstmt);
		}
	}

	@Override
	public int update(UpdateQuery updateQuery, EntityDetails entityDetails)
	{
		logger.trace("Started method: update");
		logger.debug("Trying to update entity in table '{}' using query: ", updateQuery.getTableName(), updateQuery);
		
		PreparedStatement pstmt = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.UPDATE_QUERY, "query", updateQuery);
			
			logger.debug("Built update query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			Object value = null;
			List<Closeable> closeables = new ArrayList<>();
			
			for(ColumnParam column: updateQuery.getColumns())
			{
				value = column.getValue();
				
				if(value instanceof LobData)
				{
					LobData lobData = (LobData)value;
					closeables.add(lobData);
					
					if(lobData.isTextStream())
					{
						pstmt.setCharacterStream(index, lobData.openReader() );
					}
					else
					{
						pstmt.setBinaryStream(index,  lobData.openStream() );
					}
				}
				else
				{
					pstmt.setObject(index, value);
				}

				params.add(value);
				index++;
			}
			
			for(QueryCondition condition: updateQuery.getConditions())
			{
				pstmt.setObject(index, condition.getValue());
				params.add(condition.getValue());
				
				index++;
			}
			
			logger.debug("Executing using params: {}", params);
			
			int count = pstmt.executeUpdate();
			
			//close any open closeables (like blob streams)
			for(Closeable closeable : closeables)
			{
				closeable.close();
			}

			logger.debug("Updated " + count + " records in table: " + updateQuery.getTableName());
			
			transaction.commit();
			return count;
		}catch(Exception ex)
		{
			logger.error("An error occurred while updating entity(s) to table '" 
					+ updateQuery.getTableName() + "' using query: " + updateQuery, ex);

			SqlExceptionHandler.handleException("An error occurred while updating entity(s) to table '" 
					+ updateQuery.getTableName() + "'", ex, entityDetailsFactory, false);
			return -1;
		}finally
		{
			closeResources(null, pstmt);
		}
	}
	

	@Override
	public int delete(DeleteQuery deleteQuery, EntityDetails entityDetails)
	{
		logger.trace("Started method: delete");
		logger.debug("Deleting rows from table '{}' using query: {}", deleteQuery.getTableName(), deleteQuery);
		
		PreparedStatement pstmt = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.DELETE_QUERY, "query", deleteQuery);
			
			logger.debug("Built delete query as: \n\t{}", query);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			List<Object> params = new ArrayList<>();
			
			for(QueryCondition condition: deleteQuery.getConditions())
			{
				pstmt.setObject(index, condition.getValue());
				params.add(condition.getValue());
				
				index++;
			}
			
			logger.debug("Executing using params: {}", params);
			
			int deleteCount = pstmt.executeUpdate();
			
			logger.debug("Deleted " + deleteCount + " records from table: " + deleteQuery.getTableName());
			
			transaction.commit();
			return deleteCount;
		}catch(Exception ex)
		{
			logger.error("An error occurred while deleting rows from table '" + deleteQuery.getTableName() + "' using query: " + deleteQuery, ex);

			SqlExceptionHandler.handleException("An error occurred while deleting rows from table '" 
					+ deleteQuery.getTableName() + "'", ex, entityDetailsFactory, true);

			return -1;
		}finally
		{
			closeResources(null, pstmt);
		}
	}
	
	protected PreparedStatement buildPreparedStatement(RdbmsTransaction transaction, String queryName, Object... params) throws SQLException
	{
		List<Object> paramValues = new ArrayList<>();
		
		String query = rdbmsConfig.buildQuery(queryName, paramValues, params);
		
		logger.debug("Built query as: \n\t{}", query);
		
		Connection connection = transaction.getConnection();
		PreparedStatement pstmt = connection.prepareStatement(query);
		int index = 1;
		
		for(Object value: paramValues)
		{
			pstmt.setObject(index, value);
			index++;
		}
		
		logger.debug("Executing using params: {}", paramValues);
		
		return pstmt;
	}
	
	private char[] convertClob(Clob clob)
	{
		try
		{
			Reader reader = clob.getCharacterStream();
			char buf[] = new char[1024];
			int len = 0;
			CharArrayWriter charWriter = new CharArrayWriter();
			
			while((len = reader.read(buf)) > 0)
			{
				charWriter.write(buf, 0, len);
			}
	
			reader.close();
			return charWriter.toCharArray();
		}catch(Exception ex)
		{
			logger.error("An error occurred while reading clob", ex);
			throw new IllegalStateException("An error occurred while reading clob", ex);
		}
		
	}

	private byte[] convertBlob(Blob blob)
	{
		try
		{
			InputStream is = blob.getBinaryStream();
			byte buf[] = new byte[1024];
			int len = 0;
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			
			while((len = is.read(buf)) > 0)
			{
				bos.write(buf, 0, len);
			}
	
			is.close();
			return bos.toByteArray();
		}catch(Exception ex)
		{
			logger.error("An error occurred while reading blob", ex);
			throw new IllegalStateException("An error occurred while reading blob", ex);
		}
		
	}
	
	private void addParamsRecursively(QueryCondition condition, PreparedStatement stmt, List<Object> params) throws SQLException
	{
		//for null based conditions templates should take care of nulls
		if(condition.getValue() != null)
		{
			stmt.setObject(params.size() + 1, condition.getValue());
			params.add(condition.getValue());
		}
		
		if(condition.getGroupedConditions() == null)
		{
			return;
		}
		
		for(QueryCondition grpCondition : condition.getGroupedConditions())
		{
			addParamsRecursively(grpCondition, stmt, params);
		}
	}

	@Override
	public List<Record> executeFinder(FinderQuery findQuery, EntityDetails entityDetails, IFinderRecordProcessor recordProcessor)
	{
		logger.trace("Started method: executeFinder");
		logger.debug("Fetching records from table '{}' using query: {}", findQuery.getTableName(), findQuery);
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.FINDER_QUERY, "query", findQuery);
			
			logger.debug("Built find query as: \n\t{}", query);
			List<Object> params = new ArrayList<>();
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			
			for(QueryCondition condition: findQuery.getConditions())
			{
				addParamsRecursively(condition, pstmt, params);
			}
			
			logger.debug("Executing using params: {}", params);
			
			rs = pstmt.executeQuery();
			
			List<Record> records = new ArrayList<>();
			ResultSetMetaData metaData = rs.getMetaData();
			Record  rec = null;
			int colCount = metaData.getColumnCount();
			String colNames[] = null;
			Object cellValue = null;
			long recordNo = -1;
			IFinderRecordProcessor.Action action = null;
			
			while(rs.next())
			{
				recordNo++;
				
				//if records are avialable fetch columns names, so taht same name objects
				//are shared across the reocrds
				if(colNames == null)
				{
					colNames = new String[colCount];
					
					for(int i = 0 ; i < colCount ; i++)
					{
						colNames[i] = metaData.getColumnLabel(i + 1);
					}
				}
				
				rec = new Record(colCount);
				
				//fetch column values for each record
				for(int i = 0 ; i < colCount ; i++)
				{
					cellValue = rs.getObject(i + 1);
					
					if(cellValue instanceof Clob)
					{
						cellValue = convertClob((Clob)cellValue);
					}
					else if(cellValue instanceof Blob)
					{
						cellValue = convertBlob((Blob)cellValue);
					}
					
					rec.set(i, colNames[i], cellValue);
				}
				
				if(recordProcessor != null)
				{
					//check the action to be performed
					action = recordProcessor.process(recordNo, rec);
					
					if(action == Action.STOP)
					{
						//stop further processing
						break;
					}
					
					if(action == Action.IGNORE)
					{
						//ignore current record and go to next record
						continue;
					}
				}
				
				records.add(rec);
			}
			
			logger.debug("Found " + records.size() + " records found from table: " + findQuery.getTableName());
			
			transaction.commit();
			return records;
		}catch(Exception ex)
		{
			logger.error("An error occurred while finding rows from table '" 
					+ findQuery.getTableName() + "' using query: " + findQuery, ex);
			
			throw new PersistenceException("An error occurred while finding rows from table '" 
						+ findQuery.getTableName() + "' using query: " + findQuery, ex);
		}finally
		{
			closeResources(rs, pstmt);
		}
	}
	
	/* (non-Javadoc)
	 * @see com.yukthi.persistence.IDataStore#executeNativeFinder(java.lang.String, java.lang.Object)
	 */
	public List<Record> executeNativeFinder(String queryName, Object context)
	{
		logger.trace("Started method: executeNativeFinder with query - {}", queryName);
		
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			List<Object> params = new ArrayList<>();
			String query = nativeQueryFactory.buildQuery(queryName, params, context);
			
			logger.debug("Built native find query as: \n\t{}", query);
			logger.debug("Executing using params: {}", params);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);

			int paramCount = params.size();
			
			for(int i = 0; i < paramCount; i++)
			{
				pstmt.setObject(i + 1, params.get(i));
			}
			
			rs = pstmt.executeQuery();
			
			List<Record> records = new ArrayList<>();
			ResultSetMetaData metaData = rs.getMetaData();
			Record  rec = null;
			int colCount = metaData.getColumnCount();
			String colNames[] = null;
			Object cellValue = null;
			
			while(rs.next())
			{
				//if records are available fetch columns names, so that same name objects
				//are shared across the records
				if(colNames == null)
				{
					colNames = new String[colCount];
					
					for(int i = 0 ; i < colCount ; i++)
					{
						colNames[i] = metaData.getColumnLabel(i + 1);
					}
				}
				
				rec = new Record(colCount);
				
				//fetch column values for each record
				for(int i = 0 ; i < colCount ; i++)
				{
					cellValue = rs.getObject(i + 1);
					
					if(cellValue instanceof Clob)
					{
						cellValue = convertClob((Clob)cellValue);
					}
					else if(cellValue instanceof Blob)
					{
						cellValue = convertBlob((Blob)cellValue);
					}
					
					rec.set(i, colNames[i], cellValue);
				}
				
				records.add(rec);
			}
			
			logger.debug("Found " + records.size() + " records found from table");
			
			transaction.commit();
			return records;
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing native finder query - " + queryName, ex); 
			
			throw new PersistenceException("An error occurred while executing native finder query - " + queryName, ex);
		}finally
		{
			closeResources(rs, pstmt);
		}
	}

	@Override
	public int executeNativeDml(String queryName, Object context)
	{
		logger.trace("Started method: executeNativeDml - " + queryName);
		
		PreparedStatement pstmt = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			List<Object> params = new ArrayList<>();
			String query = nativeQueryFactory.buildQuery(queryName, params, context);
			
			logger.debug("Built update query as: \n\t{}", query);
			logger.debug("Executing using params: {}", params);
			
			Connection connection = transaction.getTransaction().getConnection();
			pstmt = connection.prepareStatement(query);
			int index = 1;
			Object value = null;
			List<Closeable> closeables = new ArrayList<>();
			int paramCount = params.size();
			
			for(int i = 0; i < paramCount; i++)
			{
				value = params.get(i);
				
				if(value instanceof LobData)
				{
					LobData lobData = (LobData)value;
					closeables.add(lobData);
					
					if(lobData.isTextStream())
					{
						pstmt.setCharacterStream(index, lobData.openReader() );
					}
					else
					{
						pstmt.setBinaryStream(index,  lobData.openStream() );
					}
				}
				else
				{
					pstmt.setObject(index, value);
				}

				params.add(value);
				index++;
			}
			
			
			int count = pstmt.executeUpdate();
			
			//close any open closeables (like blob streams)
			for(Closeable closeable : closeables)
			{
				closeable.close();
			}

			logger.debug("Affected {} records by query - {}", count, queryName);
			
			transaction.commit();
			return count;
		}catch(Exception ex)
		{
			logger.error("An error occurred while executing native DML - " + queryName, ex); 

			SqlExceptionHandler.handleException("An error occurred while executing native DML - " + queryName, ex, entityDetailsFactory, false);
			return -1;
		}finally
		{
			closeResources(null, pstmt);
		}
	}

	/* (non-Javadoc)
	 * @see com.fw.persistence.IDataStore#dropTable(com.fw.persistence.query.DropTableQuery)
	 */
	@Override
	public void dropTable(DropTableQuery dropQuery)
	{
		logger.trace("Started method: dropTable");
		logger.debug("Trying to drop table '{}' using query: {}", dropQuery.getTableName(), dropQuery);
		
		Statement stmt = null;
		ResultSet rs = null;
		
		try(TransactionWrapper<RdbmsTransaction> transaction = transactionManager.newOrExistingTransaction())
		{
			Connection connection = transaction.getTransaction().getConnection();
			
			//check if table already exists
			DatabaseMetaData metaData = connection.getMetaData();
			rs = metaData.getTables(null, null, dropQuery.getTableName(), null);
			
			if(!rs.next())
			{
				logger.info("No table exists with name '{}'. So ignoring drop request", dropQuery.getTableName());
				return;
			}
			
			
			//execute drop query
			String query = rdbmsConfig.buildQuery(RdbmsConfiguration.DROP_QUERY, "query", dropQuery);
			
			logger.debug("Built drop query as: \n\t{}", query);
			
			stmt = connection.createStatement();
			
			stmt.execute(query);
			connection.commit();
		}catch(Exception ex)
		{
			logger.error("An error occurred while dropping table '" 
					+ dropQuery.getTableName() + "' using query: " + dropQuery, ex);
			
			throw new PersistenceException("An error occurred while dropping table '" 
						+ dropQuery.getTableName() + "' using query: " + dropQuery, ex);
		}finally
		{
			closeResources(rs, stmt);
		}
	}

	/* (non-Javadoc)
	 * @see com.fw.persistence.IDataStore#isExplicitChildDeleteRequired()
	 */
	@Override
	public boolean isExplicitForeignCheckRequired()
	{
		return false;
	}

	/* (non-Javadoc)
	 * @see com.fw.persistence.IDataStore#isExplicitUniqueCheckRequired()
	 */
	@Override
	public boolean isExplicitUniqueCheckRequired()
	{
		return false;
	}
}
