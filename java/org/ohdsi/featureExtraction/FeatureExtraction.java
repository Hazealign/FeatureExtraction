/*******************************************************************************
 * Copyright 2017 Observational Health Data Sciences and Informatics
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.ohdsi.featureExtraction;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONWriter;
import org.ohdsi.sql.SqlRender;
import org.ohdsi.sql.StringUtils;

/**
 * FeatureExtraction engine. Generates SQL for constructing and downloading features for cohorts of interest.
 */
public class FeatureExtraction {

	private static ReentrantLock							lock							= new ReentrantLock();
	private static Map<String, PrespecAnalysis>				nameToPrespecAnalysis			= null;
	private static Map<String, PrespecAnalysis>				nameToPrespecTemporalAnalysis	= null;
	private static Map<String, String>						nameToSql						= null;
	private static String									createCovRefTableSql			= null;
	private static Map<String, Map<String, OtherParameter>>	typeToNameToOtherParameters		= null;
	private static Set<String>								otherParameterNames				= null;

	private static String									TEMPORAL						= "temporal";
	private static String									ANALYSIS_ID						= "analysisId";
	private static String									ANALYSIS_NAME					= "analysisName";
	private static String									DESCRIPTION						= "description";
	private static String									IS_DEFAULT						= "isDefault";
	private static String									SQL_FILE_NAME					= "sqlFileName";
	private static String									ANALYSES						= "analyses";
	private static String									PARAMETERS						= "parameters";
	private static String									INCLUDED_COVARIATE_CONCEPT_IDS	= "includedCovariateConceptIds";
	private static String									ADD_DESCENDANTS_TO_INCLUDE		= "addDescendantsToInclude";
	private static String									EXCLUDED_COVARIATE_CONCEPT_IDS	= "excludedCovariateConceptIds";
	private static String									ADD_DESCENDANTS_TO_EXCLUDE		= "addDescendantsToExclude";
	private static String									INCLUDED_COVARIATE_IDS			= "includedCovariateIds";

	private static String									COMMON_TYPE						= "common";
	private static String									DAYS_TYPE						= "days";
	private static String									TEMPORAL_TYPE					= "temporal";

	private static String									ADD_DESCENDANTS_SQL				= "SELECT descendant_concept_id AS id\nINTO @target_temp\nFROM @cdm_database_schema.concept_ancestor\nINNER JOIN @source_temp\n\tON ancestor_concept_id = id;\n\n";

	public static void main(String[] args) {
		init("C:/Users/mschuemi/git/FeatureExtraction/inst");
		// init("C:/R/R-3.3.1/library/FeatureExtraction");

		// System.out.println(convertSettingsPrespecToDetails("{\"temporal\":false,\"DemographicsGender\":true,\"DemographicsAge\":true,\"longTermStartDays\":-365,\"mediumTermStartDays\":-180,\"shortTermStartDays\":-30,\"endDays\":0,\"includedCovariateConceptIds\":[],\"addDescendantsToInclude\":false,\"excludedCovariateConceptIds\":[1,2,3],\"addDescendantsToExclude\":false,\"includedCovariateIds\":[]}"));
		// System.out.println(getDefaultPrespecAnalyses());
		//
		// System.out.println(convertSettingsPrespecToDetails(getDefaultPrespecAnalyses()));
		//
		System.out.println(getDefaultPrespecTemporalAnalyses());
		// System.out.println(convertSettingsPrespecToDetails(getDefaultPrespecTemporalAnalyses()));
		// String settings =
		// "{\"temporal\":false,\"analyses\":[{\"analysisId\":301,\"sqlFileName\":\"DomainConcept.sql\",\"parameters\":{\"analysisId\":301,\"startDay\":-365,\"endDay\":0,\"inpatient\":\"\",\"domainTable\":\"drug_exposure\",\"domainConceptId\":\"drug_concept_id\",\"domainStartDate\":\"drug_exposure_start_date\",\"domainEndDate\":\"drug_exposure_start_date\"},\"addDescendantsToExclude\":true,\"includedCovariateConceptIds\":[1,2,3],\"excludedCovariateConceptIds\":[1,2,3],\"addDescendantsToInclude\":true,\"includedCovariateIds\":[1]}]}";
		// String settings = convertSettingsPrespecToDetails(getDefaultPrespecAnalyses());
		// System.out.println(createSql(settings, false, "#temp_cohort", "row_id", -1, "cdm_synpuf"));
		 System.out.println(createSql(getDefaultPrespecAnalyses(), true, "#temp_cohort", "row_id", -1, "cdm_synpuf"));
//		System.out.println(createSql(getDefaultPrespecTemporalAnalyses(), false, "#temp_cohort", "row_id", -1, "cdm_synpuf"));
	}

	/**
	 * Initialize the FeatureExtraction engine, preloading the necessary content. packageFolder Need to specify for R package, else will load from the JAR file.
	 */
	public static void init(String packageFolder) {
		if (nameToPrespecAnalysis != null)
			return;
		else {
			lock.lock();
			if (otherParameterNames == null) { // Could have been loaded before acquiring the lock
				otherParameterNames = new HashSet<String>();
				loadOtherParameters(packageFolder);
				nameToSql = new HashMap<String, String>();
				nameToPrespecAnalysis = loadPrespecAnalysis(packageFolder, "PrespecAnalyses.csv");
				nameToPrespecTemporalAnalysis = loadPrespecAnalysis(packageFolder, "PrespecTemporalAnalyses.csv");
				loadTemplateSql(packageFolder);
				createCovRefTableSql = loadSqlFile(packageFolder, "CreateCovAnalysisRefTables.sql");
			}
			lock.unlock();
		}
	}

	private static void loadOtherParameters(String packageFolder) {
		typeToNameToOtherParameters = new HashMap<String, Map<String, OtherParameter>>();
		try {
			InputStream inputStream;
			if (packageFolder == null) // Use CSV file in JAR
				inputStream = FeatureExtraction.class.getResourceAsStream("OtherParameters.csv");
			else
				inputStream = new FileInputStream(packageFolder + "/csv/OtherParameters.csv");
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

			String line;
			List<String> header = null;
			while ((line = bufferedReader.readLine()) != null) {
				List<String> row = line2columns(line);
				if (header == null) {
					header = row;
				} else {
					OtherParameter otherParameter = new OtherParameter();
					for (int i = 0; i < header.size(); i++)
						if (header.get(i).equals("type"))
							otherParameter.type = row.get(i);
						else if (header.get(i).equals("name"))
							otherParameter.name = row.get(i);
						// else if (header.get(i).equals("description"))
						// otherParameter.description = row.get(i);
						else if (header.get(i).equals("defaultValue"))
							otherParameter.defaultValue = new JSONObject("{value:" + row.get(i) + "}").get("value");
					Map<String, OtherParameter> nameToOtherParameter = typeToNameToOtherParameters.get(otherParameter.type);
					if (nameToOtherParameter == null) {
						nameToOtherParameter = new HashMap<String, FeatureExtraction.OtherParameter>();
						typeToNameToOtherParameters.put(otherParameter.type, nameToOtherParameter);
					}
					nameToOtherParameter.put(otherParameter.name, otherParameter);
					otherParameterNames.add(otherParameter.name);
				}
			}
		} catch (UnsupportedEncodingException e) {
			System.err.println("Computer does not support UTF-8 encoding");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static void loadTemplateSql(String packageFolder) {
		Set<String> sqlFileNames = nameToSql.keySet();
		nameToSql = new HashMap<String, String>();
		for (String sqlFileName : sqlFileNames) {
			try {
				InputStream inputStream;
				if (packageFolder == null) // Use file in JAR
					inputStream = FeatureExtraction.class.getResourceAsStream(sqlFileName);
				else
					inputStream = new FileInputStream(packageFolder + "/sql/sql_server/" + sqlFileName);
				BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
				StringBuilder sql = new StringBuilder();
				String line;
				while ((line = bufferedReader.readLine()) != null)
					sql.append(line + "\n");
				nameToSql.put(sqlFileName, sql.toString());
			} catch (UnsupportedEncodingException e) {
				System.err.println("Computer does not support UTF-8 encoding");
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private static String loadSqlFile(String packageFolder, String sqlFileName) {
		try {
			InputStream inputStream;
			if (packageFolder == null) // Use file in JAR
				inputStream = FeatureExtraction.class.getResourceAsStream(sqlFileName);
			else
				inputStream = new FileInputStream(packageFolder + "/sql/sql_server/" + sqlFileName);
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
			StringBuilder sql = new StringBuilder();
			String line;
			while ((line = bufferedReader.readLine()) != null)
				sql.append(line + "\n");
			return sql.toString();
		} catch (UnsupportedEncodingException e) {
			System.err.println("Computer does not support UTF-8 encoding");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	private static Map<String, PrespecAnalysis> loadPrespecAnalysis(String packageFolder, String filename) {
		LinkedHashMap<String, PrespecAnalysis> nameToPrespecAnalysis = new LinkedHashMap<String, PrespecAnalysis>();
		try {
			InputStream inputStream;
			if (packageFolder == null) // Use CSV file in JAR
				inputStream = FeatureExtraction.class.getResourceAsStream(filename);
			else
				inputStream = new FileInputStream(packageFolder + "/csv/" + filename);
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

			String line;
			List<String> header = null;
			while ((line = bufferedReader.readLine()) != null) {
				List<String> row = line2columns(line);
				if (header == null) {
					header = row;
				} else {
					PrespecAnalysis prespecAnalysis = new PrespecAnalysis();
					for (int i = 0; i < header.size(); i++)
						if (header.get(i).equals(ANALYSIS_ID))
							prespecAnalysis.analysisId = Integer.parseInt(row.get(i));
						else if (header.get(i).equals(ANALYSIS_NAME))
							prespecAnalysis.analysisName = row.get(i);
						else if (header.get(i).equals(SQL_FILE_NAME))
							prespecAnalysis.sqlFileName = row.get(i);
						else if (header.get(i).equals(IS_DEFAULT))
							prespecAnalysis.isDefault = Boolean.parseBoolean(row.get(i));
						// else if (header.get(i).equals(DESCRIPTION))
						// prespecAnalysis.description = row.get(i);
						else
							prespecAnalysis.keyToValue.put(header.get(i), row.get(i));
					nameToSql.put(prespecAnalysis.sqlFileName, null);
					nameToPrespecAnalysis.put(prespecAnalysis.analysisName, prespecAnalysis);
				}
			}
		} catch (UnsupportedEncodingException e) {
			System.err.println("Computer does not support UTF-8 encoding");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return nameToPrespecAnalysis;
	}

	/**
	 * Creates a default settings object
	 * 
	 * @return A JSON string
	 */
	public static String getDefaultPrespecAnalyses() {
		StringWriter stringWriter = new StringWriter();
		JSONWriter jsonWriter = new JSONWriter(stringWriter);
		jsonWriter.object();
		jsonWriter.key(TEMPORAL);
		jsonWriter.value(false);
		for (PrespecAnalysis prespecAnalysis : nameToPrespecAnalysis.values())
			if (prespecAnalysis.isDefault) {
				jsonWriter.key(prespecAnalysis.analysisName);
				jsonWriter.value(true);
			}
		for (OtherParameter otherParameter : typeToNameToOtherParameters.get(COMMON_TYPE).values()) {
			jsonWriter.key(otherParameter.name);
			jsonWriter.value(otherParameter.defaultValue);
		}
		for (OtherParameter otherParameter : typeToNameToOtherParameters.get(DAYS_TYPE).values()) {
			jsonWriter.key(otherParameter.name);
			jsonWriter.value(otherParameter.defaultValue);
		}
		jsonWriter.endObject();
		return stringWriter.toString();
	}

	/**
	 * Creates a default temporal settings object
	 * 
	 * @return A JSON string
	 */
	public static String getDefaultPrespecTemporalAnalyses() {
		StringWriter stringWriter = new StringWriter();
		JSONWriter jsonWriter = new JSONWriter(stringWriter);
		jsonWriter.object();
		jsonWriter.key(TEMPORAL);
		jsonWriter.value(true);
		for (PrespecAnalysis prespecAnalysis : nameToPrespecTemporalAnalysis.values())
			if (prespecAnalysis.isDefault) {
				jsonWriter.key(prespecAnalysis.analysisName);
				jsonWriter.value(true);
			}
		for (OtherParameter otherParameter : typeToNameToOtherParameters.get(COMMON_TYPE).values()) {
			jsonWriter.key(otherParameter.name);
			jsonWriter.value(otherParameter.defaultValue);
		}
		for (OtherParameter otherParameter : typeToNameToOtherParameters.get(TEMPORAL_TYPE).values()) {
			jsonWriter.key(otherParameter.name);
			jsonWriter.value(otherParameter.defaultValue);
		}
		jsonWriter.endObject();
		return stringWriter.toString();
	}

	/**
	 * Convert prespecified analysis settings to detailed analysis settings.
	 * 
	 * @param settings
	 *            Prespec analysis settings
	 * @return A JSON string
	 */
	public static String convertSettingsPrespecToDetails(String settings) {
		JSONObject jsonObject = new JSONObject(settings);
		boolean temporal = jsonObject.getBoolean(TEMPORAL);
		StringWriter stringWriter = new StringWriter();
		JSONWriter jsonWriter = new JSONWriter(stringWriter);
		jsonWriter.object();
		jsonWriter.key(TEMPORAL);
		jsonWriter.value(temporal);
		jsonWriter.key(ANALYSES);
		jsonWriter.array();
		for (String analysisName : jsonObject.keySet()) {
			if (!analysisName.equals(TEMPORAL) && !analysisName.equals(DESCRIPTION) && !otherParameterNames.contains(analysisName)
					&& jsonObject.getBoolean(analysisName)) {
				PrespecAnalysis prespecAnalysis;
				if (temporal)
					prespecAnalysis = nameToPrespecTemporalAnalysis.get(analysisName);
				else
					prespecAnalysis = nameToPrespecAnalysis.get(analysisName);
				jsonWriter.object();
				jsonWriter.key(ANALYSIS_ID);
				jsonWriter.value(prespecAnalysis.analysisId);
				jsonWriter.key(SQL_FILE_NAME);
				jsonWriter.value(prespecAnalysis.sqlFileName);

				jsonWriter.key(PARAMETERS);
				jsonWriter.object();
				jsonWriter.key(ANALYSIS_ID);
				jsonWriter.value(prespecAnalysis.analysisId);
				jsonWriter.key(ANALYSIS_NAME);
				jsonWriter.value(prespecAnalysis.analysisName);
				for (String key : prespecAnalysis.keyToValue.keySet()) {
					jsonWriter.key(key);
					Object value = prespecAnalysis.keyToValue.get(key);
					if (typeToNameToOtherParameters.get(DAYS_TYPE).keySet().contains(value))
						value = jsonObject.get((String) value);
					jsonWriter.value(value);
				}
				jsonWriter.endObject();
				for (OtherParameter otherParameter : typeToNameToOtherParameters.get(COMMON_TYPE).values()) {
					jsonWriter.key(otherParameter.name);
					jsonWriter.value(jsonObject.get(otherParameter.name));
				}
				jsonWriter.endObject();
			}
		}
		jsonWriter.endArray();
		if (temporal)
			for (String name : typeToNameToOtherParameters.get(TEMPORAL_TYPE).keySet()) {
				jsonWriter.key(name);
				jsonWriter.value(jsonObject.get(name));
			}
		jsonWriter.endObject();
		return stringWriter.toString();
	}

	/**
	 * Construct the SQL for creating and retrieving the features. The output object consists of the following main components:
	 * <ol>
	 * <li>tempTables: a list of tables to insert into temp tables on the server.</li>
	 * <li>sqlConstruction: SQL for constructing the features on the server.</li>
	 * <li>sqlQueryFeatures: SQL for fetching the features from the server. (limited to binary features when aggregated)</li>
	 * <li>sqlQueryContinuousFeatures: SQL for fetching the continuous features from the server.</li>
	 * <li>sqlQueryFeatureRef: SQL for fetching the description of the features from the server.</li>
	 * <li>sqlQueryAnalysisRef: SQL for fetching the description of the analyses from the server.</li>
	 * <li>sqlCleanup: SQL for deleting the temp tables created by sqlConstruction.</li>
	 * </ol>
	 * Note that all SQL is in the SQL Server dialect, and may need to be translated to the appropriate dialect using SqlRender's translateSql function.
	 * 
	 * @param settings
	 *            A JSON object with detailed settings.
	 * @param aggregated
	 *            Should features be constructed per person or aggregated across the cohort?
	 * @param cohortTable
	 *            The name of the cohort table. Can be a temp table (e.g. "#cohort_temp"). If it is a permanent table provide the full path, e.g.
	 *            "cdm_synpuf.dbo.cohort".
	 * @param rowIdField
	 *            The name of the field in the cohort table that will be used as the row_id field in the output (if not aggregated). Could be "subject_id"
	 *            unless subjects can appear in a cohort more than once.
	 * @param cohortDefinitionId
	 *            The ID of the cohort to characterize. If set to -1, all entries in the cohort table will be used.
	 * @param cdmDatabaseSchema
	 *            The name of the database schema that contains the OMOP CDM instance. Requires read permissions to this database. On SQL Server, this should
	 *            specify both the database and the schema, so for example 'cdm_instance.dbo'.
	 * @return A JSON object.
	 */
	public static String createSql(String settings, boolean aggregated, String cohortTable, String rowIdField, int cohortDefinitionId,
			String cdmDatabaseSchema) {
		JSONObject jsonObject = new JSONObject(settings);

		// If input in prespec analyses, convert to detailed settings:
		if (!jsonObject.has("analyses")) {
			settings = convertSettingsPrespecToDetails(settings);
			jsonObject = new JSONObject(settings);
		}

		boolean temporal = jsonObject.getBoolean(TEMPORAL);
		Map<IdSet, String> idSetToName = extractUniqueIdSets(jsonObject);

		StringWriter stringWriter = new StringWriter();
		JSONWriter jsonWriter = new JSONWriter(stringWriter);
		jsonWriter.object();

		// Add temp tables to insert
		jsonWriter.key("tempTables");
		jsonWriter.object();
		for (Map.Entry<IdSet, String> entry : idSetToName.entrySet()) {
			jsonWriter.key(entry.getValue() + (entry.getKey().addDescendants ? "_a" : ""));
			jsonWriter.object();
			jsonWriter.key("id");
			jsonWriter.value(entry.getKey().ids);
			jsonWriter.endObject();
		}
		if (temporal) {
			jsonWriter.key("#time_period");
			jsonWriter.object();
			jsonWriter.key("start_day");
			jsonWriter.value(jsonObject.get("temporalStartDays"));
			jsonWriter.key("end_day");
			jsonWriter.value(jsonObject.get("temporalEndDays"));
			jsonWriter.key("time_id");
			jsonWriter.value(createIndexArray(jsonObject.getJSONArray("temporalEndDays").length()));
			jsonWriter.endObject();
		}
		jsonWriter.endObject();

		jsonWriter.key("sqlConstruction");
		jsonWriter.value(createConstructionSql(jsonObject, idSetToName, temporal, aggregated, cohortTable, rowIdField, cohortDefinitionId, cdmDatabaseSchema));

		String sqlQueryFeatures = createQuerySql(jsonObject, aggregated, temporal);
		if (sqlQueryFeatures != null) {
			jsonWriter.key("sqlQueryFeatures");
			jsonWriter.value(sqlQueryFeatures);
		}

		if (aggregated) {
			String sqlQueryContinuousFeatures = createQueryContinuousFeaturesSql(jsonObject, temporal);
			if (sqlQueryContinuousFeatures != null) {
				jsonWriter.key("sqlQueryContinuousFeatures");
				jsonWriter.value(sqlQueryContinuousFeatures);
			}
		}

		jsonWriter.key("sqlQueryFeatureRef");
		jsonWriter.value("SELECT covariate_id, covariate_name, analysis_id, concept_id  FROM #cov_ref ORDER BY covariate_id");

		jsonWriter.key("sqlQueryAnalysisRef");
		if (temporal) {
			jsonWriter.value("SELECT analysis_id, analysis_name, domain_id, is_binary FROM #analysis_ref ORDER BY analysis_id");
		} else {
			jsonWriter.value("SELECT analysis_id, analysis_name, domain_id, start_day, end_day, is_binary FROM #analysis_ref ORDER BY analysis_id");
		}

		jsonWriter.key("sqlCleanup");
		jsonWriter.value(createCleanupSql(jsonObject, temporal));

		jsonWriter.endObject();
		return stringWriter.toString();
	}

	private static int[] createIndexArray(int length) {
		int[] index = new int[length];
		for (int i = 0; i < length; i++)
			index[i] = i + 1;
		return index;
	}

	private static Object createCleanupSql(JSONObject jsonObject, boolean temporal2) {
		List<String> tempTables = new ArrayList<String>();
		Iterator<Object> analysesIterator = jsonObject.getJSONArray(ANALYSES).iterator();
		while (analysesIterator.hasNext()) {
			JSONObject analysis = (JSONObject) analysesIterator.next();
			tempTables.add(analysis.getString("covariateTable"));
		}
		tempTables.add("#cov_ref");
		tempTables.add("#analysis_ref");
		StringBuilder sql = new StringBuilder();
		for (String tempTable : tempTables) {
			sql.append("TRUNCATE TABLE " + tempTable + ";\n");
			sql.append("DROP TABLE " + tempTable + ";\n");
		}
		return sql.toString();
	}

	private static String createQuerySql(JSONObject jsonObject, boolean aggregated, boolean temporal) {
		StringBuilder fields = new StringBuilder();
		if (aggregated) {
			fields.append("covariate_id, sum_value, average_value");
		} else {
			fields.append("covariate_id, covariate_value, row_id");
		}
		if (temporal) {
			fields.append(", time_id");
		}
		boolean hasFeature = false;
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT " + fields.toString() + "\nFROM (\n");
		Iterator<Object> analysesIterator = jsonObject.getJSONArray(ANALYSES).iterator();
		while (analysesIterator.hasNext()) {
			JSONObject analysis = (JSONObject) analysesIterator.next();
			if (!aggregated || analysis.getBoolean("isBinary")) {
				if (hasFeature)
					sql.append(" UNION ALL\n");
				sql.append("SELECT " + fields.toString() + " FROM " + analysis.getString("covariateTable"));
				hasFeature = true;
			}
		}
		if (!hasFeature)
			return null;
		sql.append("\n) all_covariates;");
		return sql.toString();
	}

	private static String createQueryContinuousFeaturesSql(JSONObject jsonObject, boolean temporal) {
		StringBuilder fields = new StringBuilder();
		fields.append(
				"covariate_id, count_value, min_value, max_value, average_value, standard_deviation, median_value, p10_value, p25_value, p75_value, p90_value");
		if (temporal) {
			fields.append(", time_id");
		}
		boolean hasFeature = false;
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT " + fields.toString() + "\nFROM (\n");
		Iterator<Object> analysesIterator = jsonObject.getJSONArray(ANALYSES).iterator();
		while (analysesIterator.hasNext()) {
			JSONObject analysis = (JSONObject) analysesIterator.next();
			if (!analysis.getBoolean("isBinary")) {
				if (hasFeature)
					sql.append(" UNION ALL\n");
				sql.append("SELECT " + fields.toString() + " FROM " + analysis.getString("covariateTable"));
				hasFeature = true;
			}
		}
		if (!hasFeature)
			return null;
		sql.append("\n) all_covariates;");
		return sql.toString();
	}

	private static String createConstructionSql(JSONObject jsonObject, Map<IdSet, String> idSetToName, boolean temporal, boolean aggregated, String cohortTable,
			String rowIdField, int cohortDefinitionId, String cdmDatabaseSchema) {
		StringBuilder sql = new StringBuilder();

		// Add descendants to ID sets if needed:
		for (Map.Entry<IdSet, String> entry : idSetToName.entrySet()) {
			if (entry.getKey().addDescendants) {
				String line = SqlRender.renderSql(ADD_DESCENDANTS_SQL, new String[] { "source_temp", "target_temp" },
						new String[] { entry.getValue() + "_a", entry.getValue() });
				sql.append(line);
			}
		}

		// Prep stuff
		sql.append(SqlRender.renderSql(createCovRefTableSql, new String[] { "temporal" }, new String[] { Boolean.toString(temporal) }));
		sql.append("\n");

		// Add analyses
		int a = 1;
		Iterator<Object> analysesIterator = jsonObject.getJSONArray(ANALYSES).iterator();
		while (analysesIterator.hasNext()) {
			JSONObject analysis = (JSONObject) analysesIterator.next();
			String covariateTable = "#cov_" + a++;
			analysis.put("covariateTable", covariateTable);
			String templateSql = nameToSql.get(analysis.get(SQL_FILE_NAME));
			JSONObject parameters = analysis.getJSONObject(PARAMETERS);
			String[] keys = new String[parameters.length() + 10];
			String[] values = new String[parameters.length() + 10];
			int i = 0;
			for (String key : parameters.keySet()) {
				keys[i] = StringUtilities.camelCaseToSnakeCase(key);
				values[i] = parameters.get(key).toString();
				i++;
			}
			keys[i] = "cohort_table";
			values[i] = cohortTable;
			i++;
			keys[i] = "row_id_field";
			values[i] = rowIdField;
			i++;
			keys[i] = "cohort_definition_id";
			values[i] = Integer.toString(cohortDefinitionId);
			i++;
			keys[i] = "cdm_database_schema";
			values[i] = cdmDatabaseSchema;
			i++;
			keys[i] = "covariate_table";
			values[i] = covariateTable;
			i++;
			keys[i] = "temporal";
			values[i] = Boolean.toString(temporal);
			i++;
			keys[i] = "aggregated";
			values[i] = Boolean.toString(aggregated);
			i++;
			keys[i] = "included_concept_table";
			values[i] = analysis.getString("incConcepts");
			i++;
			keys[i] = "excluded_concept_table";
			values[i] = analysis.getString("excConcepts");
			i++;
			keys[i] = "included_cov_table";
			values[i] = analysis.getString("incCovs");
			sql.append(SqlRender.renderSql(templateSql, keys, values));
			if (templateSql.contains("'N' AS is_binary"))
				analysis.put("isBinary", false);
			else if (sql.toString().contains("'Y' AS is_binary"))
				analysis.put("isBinary", true);
			else
				throw new RuntimeException("Unable to determine if feature is binary or not: " + analysis.get(SQL_FILE_NAME));
		}
		return sql.toString();
	}

	private static Map<IdSet, String> extractUniqueIdSets(JSONObject jsonObject) {
		Iterator<Object> analysesIterator = jsonObject.getJSONArray(ANALYSES).iterator();
		Map<IdSet, String> idSetToName = new HashMap<FeatureExtraction.IdSet, String>();
		String name;
		IdSet idSet;
		while (analysesIterator.hasNext()) {
			JSONObject analysis = (JSONObject) analysesIterator.next();
			idSet = new IdSet(analysis.getJSONArray(INCLUDED_COVARIATE_CONCEPT_IDS), analysis.getBoolean(ADD_DESCENDANTS_TO_INCLUDE));
			if (idSet.isEmpty()) {
				analysis.put("incConcepts", "");
			} else {
				name = idSetToName.get(idSet);
				if (name == null) {
					name = "#id_set_" + (idSetToName.size() + 1);
					idSetToName.put(idSet, name);
				}
				analysis.put("incConcepts", name);
			}
			idSet = new IdSet(analysis.getJSONArray(EXCLUDED_COVARIATE_CONCEPT_IDS), analysis.getBoolean(ADD_DESCENDANTS_TO_EXCLUDE));
			if (idSet.isEmpty()) {
				analysis.put("excConcepts", "");
			} else {
				name = idSetToName.get(idSet);
				if (name == null) {
					name = "#id_set_" + (idSetToName.size() + 1);
					idSetToName.put(idSet, name);
				}
				analysis.put("excConcepts", name);
			}
			idSet = new IdSet(analysis.getJSONArray(INCLUDED_COVARIATE_IDS), false);
			if (idSet.isEmpty()) {
				analysis.put("incCovs", "");
			} else {
				name = idSetToName.get(idSet);
				if (name == null) {
					name = "#id_set_" + (idSetToName.size() + 1);
					idSetToName.put(idSet, name);
				}
				analysis.put("incCovs", name);
			}
		}
		return idSetToName;
	}

	private static List<String> line2columns(String line) {
		List<String> columns = StringUtils.safeSplit(line, ',');
		for (int i = 0; i < columns.size(); i++) {
			String column = columns.get(i);
			if (column.startsWith("\"") && column.endsWith("\"") && column.length() > 1)
				column = column.substring(1, column.length() - 1);
			column = column.replace("\\\"", "\"");
			column = column.replace("\\n", "\n");
			columns.set(i, column);
		}
		return columns;
	}

	private static class PrespecAnalysis {
		public int					analysisId;
		public String				analysisName;
		public boolean				isDefault;
		public String				sqlFileName;
		// public String description;
		public Map<String, String>	keyToValue	= new LinkedHashMap<String, String>();
	}

	private static class OtherParameter {
		public String	type;
		public String	name;
		public Object	defaultValue;
		// public String description;
	}

	private static class IdSet {
		public Set<Integer>	ids				= new HashSet<Integer>();
		public boolean		addDescendants	= false;

		public IdSet(JSONArray ids, boolean addDescendants) {
			for (Object id : ids)
				this.ids.add((Integer) id);
			this.addDescendants = addDescendants;
		}

		public boolean isEmpty() {
			return (ids.size() == 0);
		}

		public boolean equals(Object other) {
			return (ids.equals(((IdSet) other).ids) && addDescendants == ((IdSet) other).addDescendants);
		}

		public int hashCode() {
			return ids.hashCode() + (addDescendants ? 1 : 0);
		}
	}
}