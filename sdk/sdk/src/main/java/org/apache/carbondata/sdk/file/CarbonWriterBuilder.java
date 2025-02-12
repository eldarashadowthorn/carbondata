/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.carbondata.sdk.file;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.carbondata.common.annotations.InterfaceAudience;
import org.apache.carbondata.common.annotations.InterfaceStability;
import org.apache.carbondata.common.constants.LoggerAction;
import org.apache.carbondata.common.exceptions.sql.InvalidLoadOptionException;
import org.apache.carbondata.common.logging.LogServiceFactory;
import org.apache.carbondata.core.constants.CarbonCommonConstants;
import org.apache.carbondata.core.datastore.filesystem.CarbonFile;
import org.apache.carbondata.core.datastore.impl.FileFactory;
import org.apache.carbondata.core.metadata.datatype.DataType;
import org.apache.carbondata.core.metadata.datatype.DataTypes;
import org.apache.carbondata.core.metadata.datatype.Field;
import org.apache.carbondata.core.metadata.datatype.MapType;
import org.apache.carbondata.core.metadata.datatype.StructField;
import org.apache.carbondata.core.metadata.schema.SchemaReader;
import org.apache.carbondata.core.metadata.schema.table.CarbonTable;
import org.apache.carbondata.core.metadata.schema.table.TableSchema;
import org.apache.carbondata.core.metadata.schema.table.TableSchemaBuilder;
import org.apache.carbondata.core.metadata.schema.table.column.CarbonColumn;
import org.apache.carbondata.core.metadata.schema.table.column.ColumnSchema;
import org.apache.carbondata.core.util.CarbonProperties;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.processing.loading.model.CarbonLoadModel;
import org.apache.carbondata.processing.loading.model.CarbonLoadModelBuilder;
import org.apache.carbondata.processing.util.CarbonLoaderUtil;
import org.apache.carbondata.sdk.file.utils.SDKUtil;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;

/**
 * Builder for {@link CarbonWriter}
 */
@InterfaceAudience.User
@InterfaceStability.Unstable
public class CarbonWriterBuilder {
  private Schema schema;
  private org.apache.avro.Schema avroSchema;
  private String path;
  //initialize with empty array , as no columns should be selected for sorting in NO_SORT
  private String[] sortColumns = new String[0];
  private int blockletSize;
  private int pageSizeInMb;
  private int blockSize;
  private long timestamp;

  // use TreeMap as keys need to be case insensitive
  private Map<String, String> options = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  private String taskNo;
  private int localDictionaryThreshold;
  private boolean isLocalDictionaryEnabled = Boolean.parseBoolean(
      CarbonCommonConstants.LOCAL_DICTIONARY_ENABLE_DEFAULT);
  private short numOfThreads;
  private Configuration hadoopConf;
  private String writtenByApp;
  private String[] invertedIndexColumns;
  private String filePath;
  private boolean isDirectory = false;
  private List<String> fileList;
  private static final Logger LOGGER =
      LogServiceFactory.getLogService(CarbonWriterBuilder.class.getName());
  private CarbonFile[] dataFiles;

  private enum WRITER_TYPE {
    CSV, AVRO, JSON, PARQUET, ORC
  }

  private WRITER_TYPE writerType;

  // can be set by withSchemaFile
  private CarbonTable carbonTable;

  /**
   * Sets the output path of the writer builder
   *
   * @param path is the absolute path where output files are written
   *             This method must be called when building CarbonWriterBuilder
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder outputPath(String path) {
    Objects.requireNonNull(path, "path should not be null");
    this.path = path;
    return this;
  }

  /**
   * sets the list of columns that needs to be in sorted order
   *
   * @param sortColumns is a string array of columns that needs to be sorted.
   *                    If it is null or by default all dimensions are selected for sorting
   *                    If it is empty array, no columns are sorted
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder sortBy(String[] sortColumns) {
    if (sortColumns != null) {
      for (int i = 0; i < sortColumns.length; i++) {
        sortColumns[i] = sortColumns[i].toLowerCase().trim();
      }
    }
    this.sortColumns = sortColumns;
    return this;
  }

  /**
   * sets the list of columns for which inverted index needs to generated
   *
   * @param invertedIndexColumns is a string array of columns for which inverted index needs to
   * generated.
   * If it is null or an empty array, inverted index will be generated for none of the columns
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder invertedIndexFor(String[] invertedIndexColumns) {
    if (invertedIndexColumns != null) {
      for (int i = 0; i < invertedIndexColumns.length; i++) {
        invertedIndexColumns[i] = invertedIndexColumns[i].toLowerCase().trim();
      }
    }
    this.invertedIndexColumns = invertedIndexColumns;
    return this;
  }

  /**
   * sets the taskNo for the writer. SDKs concurrently running
   * will set taskNo in order to avoid conflicts in file's name during write.
   *
   * @param taskNo is the TaskNo user wants to specify.
   *               by default it is system time in nano seconds.
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder taskNo(long taskNo) {
    this.taskNo = String.valueOf(taskNo);
    return this;
  }

  /**
   * sets the taskNo for the writer. SDKs concurrently running
   * will set taskNo in order to avoid conflicts in file's name during write.
   *
   * @param taskNo is the TaskNo user wants to specify.
   *               by default it is system time in nano seconds.
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder taskNo(String taskNo) {
    this.taskNo = taskNo;
    return this;
  }

  /**
   * to set the timestamp in the carbondata and carbonindex index files
   *
   * @param timestamp is a timestamp to be used in the carbondata and carbonindex index files.
   *                  By default set to zero.
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder uniqueIdentifier(long timestamp) {
    Objects.requireNonNull(timestamp, "Unique Identifier should not be null");
    this.timestamp = timestamp;
    return this;
  }

  /**
   * To support the load options for sdk writer
   *
   * @param options key,value pair of load options.
   *                supported keys values are
   *                a. bad_records_logger_enable -- true (write into separate logs), false
   *                b. bad_records_action -- FAIL, FORCE, IGNORE, REDIRECT
   *                c. bad_record_path -- path
   *                d. dateformat -- same as JAVA SimpleDateFormat
   *                e. timestampformat -- same as JAVA SimpleDateFormat
   *                f. complex_delimiter_level_1 -- value to Split the complexTypeData
   *                g. complex_delimiter_level_2 -- value to Split the nested complexTypeData
   *                h. quotechar
   *                i. escapechar
   *                j. fileheader
   *                <p>
   *                Default values are as follows.
   *                <p>
   *                a. bad_records_logger_enable -- "false"
   *                b. bad_records_action -- "FAIL"
   *                c. bad_record_path -- ""
   *                d. dateformat -- "" , uses from carbon.properties file
   *                e. timestampformat -- "", uses from carbon.properties file
   *                f. complex_delimiter_level_1 -- "\001"
   *                g. complex_delimiter_level_2 -- "\002"
   *                h. quotechar -- "\""
   *                i. escapechar -- "\\"
   *                j. fileheader -- None
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withLoadOptions(Map<String, String> options) {
    Objects.requireNonNull(options, "Load options should not be null");
    //validate the options.
    for (String option : options.keySet()) {
      if (!"bad_records_logger_enable".equalsIgnoreCase(option) &&
          !"bad_records_action".equalsIgnoreCase(option) &&
          !"bad_record_path".equalsIgnoreCase(option) &&
          !"dateformat".equalsIgnoreCase(option) &&
          !"timestampformat".equalsIgnoreCase(option) &&
          !"complex_delimiter_level_1".equalsIgnoreCase(option) &&
          !"complex_delimiter_level_2".equalsIgnoreCase(option) &&
          !"complex_delimiter_level_3".equalsIgnoreCase(option) &&
          !"quotechar".equalsIgnoreCase(option) &&
          !"escapechar".equalsIgnoreCase(option) &&
          !"binary_decoder".equalsIgnoreCase(option) &&
          !"fileheader".equalsIgnoreCase(option)) {
        throw new IllegalArgumentException("Unsupported option:" + option
            + ". Refer method header or documentation");
      }
    }

    for (Map.Entry<String, String> entry : options.entrySet()) {
      if ("bad_records_action".equalsIgnoreCase(entry.getKey())) {
        try {
          LoggerAction.valueOf(entry.getValue().toUpperCase());
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "option BAD_RECORDS_ACTION can have only either " +
                  "FORCE or IGNORE or REDIRECT or FAIL. It shouldn't be " + entry.getValue());
        }
      } else if ("bad_records_logger_enable".equalsIgnoreCase(entry.getKey())) {
        boolean isValid;
        isValid = CarbonUtil.validateBoolean(entry.getValue());
        if (!isValid) {
          throw new IllegalArgumentException("Invalid value "
              + entry.getValue() + " for key " + entry.getKey());
        }
      } else if ("quotechar".equalsIgnoreCase(entry.getKey())) {
        String quoteChar = entry.getValue();
        if (quoteChar.length() > 1) {
          throw new IllegalArgumentException("QUOTECHAR cannot be more than one character.");
        }
      } else if ("escapechar".equalsIgnoreCase(entry.getKey())) {
        String escapeChar = entry.getValue();
        if (escapeChar.length() > 1 && !CarbonLoaderUtil.isValidEscapeSequence(escapeChar)) {
          throw new IllegalArgumentException("ESCAPECHAR cannot be more than one character.");
        }
      } else if ("binary_decoder".equalsIgnoreCase(entry.getKey().toLowerCase())) {
        String binaryDecoderChar = entry.getValue();
        if (binaryDecoderChar.length() > 1 &&
            !CarbonLoaderUtil.isValidBinaryDecoder(binaryDecoderChar)) {
          throw new IllegalArgumentException("Binary decoder only support Base64, " +
              "Hex or no decode for string, don't support " + binaryDecoderChar);
        }
      }
    }

    this.options.putAll(options);
    return this;
  }

  /**
   * To support the load options for sdk writer
   *
   * @param key   the key of load option
   * @param value the value of load option
   * @return updated CarbonWriterBuilder object
   */
  public CarbonWriterBuilder withLoadOption(String key, String value) {
    Objects.requireNonNull(key, "key of load properties should not be null");
    Objects.requireNonNull(key, "value of load properties should not be null");
    Map map = new HashMap();
    map.put(key, value);
    withLoadOptions(map);
    return this;
  }

  /**
   * To support the carbon table for sdk writer
   *
   * @param table carbon table
   * @return CarbonWriterBuilder object
   */
  public CarbonWriterBuilder withTable(CarbonTable table) {
    Objects.requireNonNull(table, "Table should not be null");
    this.carbonTable = table;
    return this;
  }

  /**
   * To support the table properties for sdk writer
   *
   * @param options key,value pair of create table properties.
   * supported keys values are
   * a. table_blocksize -- [1-2048] values in MB. Default value is 1024
   * b. table_blocklet_size -- values in MB. Default value is 64 MB
   * c. local_dictionary_threshold -- positive value, default is 10000
   * d. local_dictionary_enable -- true / false. Default is false
   * e. sort_columns -- comma separated column. "c1,c2". Default all dimensions are sorted.
   *                    If empty string "" is passed. No columns are sorted
   * j. sort_scope -- "local_sort", "no_sort". default value is "local_sort"
   * k. long_string_columns -- comma separated string columns which are more than 32k length.
   *                           default value is null.
   * l. inverted_index -- comma separated string columns for which inverted index needs to be
   *                      generated
   * m. table_page_size_inmb -- [1-1755] MB.
   *
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withTableProperties(Map<String, String> options) {
    Objects.requireNonNull(options, "Table properties should not be null");
    Set<String> supportedOptions = new HashSet<>(Arrays
        .asList("table_blocksize", "table_blocklet_size", "local_dictionary_threshold",
            "local_dictionary_enable", "sort_columns", "sort_scope", "long_string_columns",
            "inverted_index", "table_page_size_inmb"));

    for (String key : options.keySet()) {
      if (!supportedOptions.contains(key.toLowerCase())) {
        throw new IllegalArgumentException(
            "Unsupported options. " + "Refer method header or documentation");
      }
    }

    for (Map.Entry<String, String> entry : options.entrySet()) {
      if ("table_blocksize".equalsIgnoreCase(entry.getKey())) {
        this.withBlockSize(Integer.parseInt(entry.getValue()));
      } else if ("table_blocklet_size".equalsIgnoreCase(entry.getKey())) {
        this.withBlockletSize(Integer.parseInt(entry.getValue()));
      } else if ("local_dictionary_threshold".equalsIgnoreCase(entry.getKey())) {
        this.localDictionaryThreshold(Integer.parseInt(entry.getValue()));
      } else if ("local_dictionary_enable".equalsIgnoreCase(entry.getKey())) {
        this.enableLocalDictionary(("true".equalsIgnoreCase(entry.getKey())));
      } else if ("sort_columns".equalsIgnoreCase(entry.getKey())) {
        //sort columns
        String[] sortColumns;
        if (entry.getValue().trim().isEmpty()) {
          sortColumns = new String[0];
        } else {
          sortColumns = entry.getValue().split(",");
        }
        this.sortBy(sortColumns);
      } else if ("sort_scope".equalsIgnoreCase(entry.getKey())) {
        this.withSortScope(entry);
      } else if ("long_string_columns".equalsIgnoreCase(entry.getKey())) {
        updateToLoadOptions(entry);
      } else if ("inverted_index".equalsIgnoreCase(entry.getKey())) {
        //inverted index columns
        String[] invertedIndexColumns;
        if (entry.getValue().trim().isEmpty()) {
          invertedIndexColumns = new String[0];
        } else {
          invertedIndexColumns = entry.getValue().split(",");
        }
        this.invertedIndexFor(invertedIndexColumns);
      } else if ("table_page_size_inmb".equalsIgnoreCase(entry.getKey())) {
        this.withPageSizeInMb(Integer.parseInt(entry.getValue()));
      }
    }
    return this;
  }

  /**
   * To support the table properties for sdk writer
   *
   * @param key   property key
   * @param value property value
   * @return CarbonWriterBuilder object
   */
  public CarbonWriterBuilder withTableProperty(String key, String value) {
    Objects.requireNonNull(key, "key of table properties should not be null");
    Objects.requireNonNull(key, "value of table properties  should not be null");
    Map map = new HashMap();
    map.put(key, value);
    withTableProperties(map);
    return this;
  }

  /**
   * To make sdk writer thread safe.
   *
   * @param numOfThreads should number of threads in which writer is called in multi-thread scenario
   *                     default sdk writer is not thread safe.
   *                     can use one writer instance in one thread only.
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withThreadSafe(short numOfThreads) {
    if (numOfThreads < 1) {
      throw new IllegalArgumentException("number of threads cannot be lesser than 1. "
          + "suggest to keep two times the number of cores available");
    }
    this.numOfThreads = numOfThreads;
    return this;
  }

  /**
   * To support hadoop configuration
   *
   * @param conf hadoop configuration support, can set s3a AK,SK,end point and other conf with this
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withHadoopConf(Configuration conf) {
    if (conf != null) {
      this.hadoopConf = conf;
    }
    return this;
  }

  /**
   * Updates the hadoop configuration with the given key value
   *
   * @param key   key word
   * @param value value
   * @return this object
   */
  public CarbonWriterBuilder withHadoopConf(String key, String value) {
    if (this.hadoopConf == null) {
      this.hadoopConf = new Configuration(true);
    }
    this.hadoopConf.set(key, value);
    return this;
  }

  /**
   * To set the carbondata file size in MB between 1MB-2048MB
   *
   * @param blockSize is size in MB between 1MB to 2048 MB
   *                  default value is 1024 MB
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withBlockSize(int blockSize) {
    if (blockSize <= 0 || blockSize > 2048) {
      throw new IllegalArgumentException("blockSize should be between 1 MB to 2048 MB");
    }
    this.blockSize = blockSize;
    return this;
  }

  /**
   * @param localDictionaryThreshold is localDictionaryThreshold, default is 10000
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder localDictionaryThreshold(int localDictionaryThreshold) {
    if (localDictionaryThreshold <= 0) {
      throw new IllegalArgumentException(
          "Local Dictionary Threshold should be greater than 0");
    }
    this.localDictionaryThreshold = localDictionaryThreshold;
    return this;
  }

  /**
   * @param appName appName which is writing the carbondata files
   * @return
   */
  public CarbonWriterBuilder writtenBy(String appName) {
    this.writtenByApp = appName;
    return this;
  }

  /**
   * @param enableLocalDictionary enable local dictionary, default is false
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder enableLocalDictionary(boolean enableLocalDictionary) {
    this.isLocalDictionaryEnabled = enableLocalDictionary;
    return this;
  }

  /**
   * To set the blocklet size of CarbonData file
   *
   * @param blockletSize is blocklet size in MB
   *                     default value is 64 MB
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withBlockletSize(int blockletSize) {
    if (blockletSize <= 0) {
      throw new IllegalArgumentException("blockletSize should be greater than zero");
    }
    this.blockletSize = blockletSize;
    return this;
  }

  /**
   * To set the blocklet size of CarbonData file
   *
   * @param pageSizeInMb is page size in MB
   * @return updated CarbonWriterBuilder
   */
  public CarbonWriterBuilder withPageSizeInMb(int pageSizeInMb) {
    if (pageSizeInMb < 1 || pageSizeInMb > 1755) {
      throw new IllegalArgumentException("pageSizeInMb must be 1 MB - 1755 MB");
    }
    this.pageSizeInMb = pageSizeInMb;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts row in CSV format
   *
   * @param schema carbon Schema object {org.apache.carbondata.sdk.file.Schema}
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withCsvInput(Schema schema) {
    Objects.requireNonNull(schema, "schema should not be null");
    if (this.schema != null) {
      throw new IllegalArgumentException("schema should be set only once");
    }
    this.schema = schema;
    this.writerType = WRITER_TYPE.CSV;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts row in CSV format
   *
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withCsvInput() {
    this.writerType = WRITER_TYPE.CSV;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts row in CSV format
   *
   * @param jsonSchema json Schema string
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withCsvInput(String jsonSchema) {
    Objects.requireNonNull(jsonSchema, "schema should not be null");
    if (this.schema != null) {
      throw new IllegalArgumentException("schema should be set only once");
    }
    this.schema = Schema.parseJson(jsonSchema);
    this.writerType = WRITER_TYPE.CSV;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts Avro object
   *
   * @param avroSchema avro Schema object {org.apache.avro.Schema}
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withAvroInput(org.apache.avro.Schema avroSchema) {
    Objects.requireNonNull(avroSchema, "Avro schema should not be null");
    this.avroSchema = avroSchema;
    this.schema = AvroCarbonWriter.getCarbonSchemaFromAvroSchema(avroSchema);
    this.writerType = WRITER_TYPE.AVRO;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts Avro object
   *
   * @param schema avro Schema object {org.apache.avro.Schema}
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withAvroInput(String schema) {
    this.avroSchema = new org.apache.avro.Schema.Parser().parse(schema);
    Objects.requireNonNull(avroSchema, "Avro schema should not be null");
    this.schema = AvroCarbonWriter.getCarbonSchemaFromAvroSchema(avroSchema);
    this.writerType = WRITER_TYPE.AVRO;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts Json object
   *
   * @param carbonSchema carbon Schema object
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withJsonInput(Schema carbonSchema) {
    Objects.requireNonNull(carbonSchema, "schema should not be null");
    if (this.schema != null) {
      throw new IllegalArgumentException("schema should be set only once");
    }
    this.schema = carbonSchema;
    this.writerType = WRITER_TYPE.JSON;
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts loading CSV files.
   *
   * @param filePath absolute path under which files should be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withCsvPath(String filePath) throws IOException {
    this.validateFilePath(filePath);
    this.filePath = filePath;
    this.setIsDirectory(filePath);
    this.withCsvInput();
    this.dataFiles = this.extractDataFiles(CarbonCommonConstants.CSV_FILE_EXTENSION);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts CSV files directory and
   * list of file which has to be loaded.
   *
   * @param filePath directory where the CSV file exists.
   * @param fileList list of files which has to be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withCsvPath(String filePath, List<String> fileList)
      throws IOException {
    this.fileList = fileList;
    this.withCsvPath(filePath);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts loading JSON files.
   *
   * @param filePath absolute path under which files should be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withJsonPath(String filePath) throws IOException {
    this.validateFilePath(filePath);
    this.filePath = filePath;
    this.setIsDirectory(filePath);
    this.withJsonInput();
    this.dataFiles = this.extractDataFiles(CarbonCommonConstants.JSON_FILE_EXTENSION);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts JSON file directory and
   * list of file which has to be loaded.
   *
   * @param filePath directory where the json file exists.
   * @param fileList list of files which has to be loaded.
   * @return CarbonWriterBuilder
   * @throws IOException
   */
  public CarbonWriterBuilder withJsonPath(String filePath, List<String> fileList)
      throws IOException {
    this.fileList = fileList;
    this.withJsonPath(filePath);
    return this;
  }

  private void validateFilePath(String filePath) {
    if (StringUtils.isEmpty(filePath)) {
      throw new IllegalArgumentException("filePath can not be empty");
    }
  }

  /**
   * to build a {@link CarbonWriter}, which accepts loading Parquet files.
   *
   * @param filePath absolute path under which files should be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withParquetPath(String filePath) throws IOException {
    this.validateFilePath(filePath);
    this.filePath = filePath;
    this.setIsDirectory(filePath);
    this.writerType = WRITER_TYPE.PARQUET;
    CarbonFile[] dataFiles = this.extractDataFiles(CarbonCommonConstants.PARQUET_FILE_EXT);
    org.apache.avro.Schema parquetSchema = ParquetCarbonWriter
        .extractParquetSchema(dataFiles[0], this.hadoopConf);
    this.dataFiles = dataFiles;
    this.avroSchema = parquetSchema;
    this.schema = AvroCarbonWriter.getCarbonSchemaFromAvroSchema(this.avroSchema);
    return this;
  }

  private void setIsDirectory(String filePath) {
    if (this.hadoopConf == null) {
      this.hadoopConf = new Configuration(FileFactory.getConfiguration());
    }
    CarbonFile carbonFile = FileFactory.getCarbonFile(filePath, hadoopConf);
    this.isDirectory = carbonFile.isDirectory();
  }

  /**
   * to build a {@link CarbonWriter}, which accepts parquet files directory and
   * list of file which has to be loaded.
   *
   * @param filePath directory where the parquet file exists.
   * @param fileList list of files which has to be loaded.
   * @return CarbonWriterBuilder
   * @throws IOException
   */
  public CarbonWriterBuilder withParquetPath(String filePath, List<String> fileList)
      throws IOException {
    this.fileList = fileList;
    this.withParquetPath(filePath);
    return this;
  }

  private CarbonFile[] extractDataFiles(String suf) {
    List<CarbonFile> dataFiles;
    if (this.isDirectory) {
      if (CollectionUtils.isEmpty(this.fileList)) {
        dataFiles = SDKUtil.extractFilesFromFolder(this.filePath, suf, this.hadoopConf);
      } else {
        dataFiles = this.appendFileListWithPath();
      }
    } else {
      dataFiles = new ArrayList<>();
      dataFiles.add(FileFactory.getCarbonFile(this.filePath, this.hadoopConf));
    }
    if (CollectionUtils.isEmpty(dataFiles)) {
      throw new RuntimeException("Data files can't be empty.");
    }
    return dataFiles.toArray(new CarbonFile[0]);
  }

  /**
   * to build a {@link CarbonWriter}, which accepts loading ORC files.
   *
   * @param filePath absolute path under which files should be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withOrcPath(String filePath) throws IOException {
    this.validateFilePath(filePath);
    this.filePath = filePath;
    this.setIsDirectory(filePath);
    this.writerType = WRITER_TYPE.ORC;
    Map<String, String> options = new HashMap<>();
    options.put("complex_delimiter_level_1",
        CarbonCommonConstants.COMPLEX_DELIMITER_LEVEL_1_DEFAULT);
    options.put("complex_delimiter_level_2",
        CarbonCommonConstants.COMPLEX_DELIMITER_LEVEL_2_DEFAULT);
    options.put("complex_delimiter_level_3",
        CarbonCommonConstants.COMPLEX_DELIMITER_LEVEL_3_DEFAULT);
    this.withLoadOptions(options);
    CarbonFile[] dataFiles = this.extractDataFiles(CarbonCommonConstants.ORC_FILE_EXTENSION);
    this.dataFiles = dataFiles;
    this.schema = ORCCarbonWriter.extractOrcFileSchema(dataFiles[0], this.hadoopConf);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts orc files directory and
   * list of file which has to be loaded.
   *
   * @param filePath directory where the orc file exists.
   * @param fileList list of files which has to be loaded.
   * @return CarbonWriterBuilder
   * @throws IOException
   */
  public CarbonWriterBuilder withOrcPath(String filePath, List<String> fileList)
      throws IOException {
    this.fileList = fileList;
    this.withOrcPath(filePath);
    return this;
  }

  private List<CarbonFile> appendFileListWithPath() {
    List<CarbonFile> dataFiles = new ArrayList<>();
    for (String file : this.fileList) {
      dataFiles.add(FileFactory.getCarbonFile(this.filePath +
          CarbonCommonConstants.FILE_SEPARATOR + file, this.hadoopConf));
    }
    return dataFiles;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts loading AVRO files.
   *
   * @param filePath absolute path under which files should be loaded.
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withAvroPath(String filePath) throws IOException {
    this.validateFilePath(filePath);
    this.filePath = filePath;
    this.setIsDirectory(filePath);
    this.writerType = WRITER_TYPE.AVRO;
    CarbonFile[] dataFiles = this.extractDataFiles(CarbonCommonConstants.AVRO_FILE_EXTENSION);
    this.avroSchema = AvroCarbonWriter.extractAvroSchema(dataFiles[0], this.hadoopConf);
    this.dataFiles = dataFiles;
    this.schema = AvroCarbonWriter.getCarbonSchemaFromAvroSchema(this.avroSchema);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts avro file directory and
   * list of file which has to be loaded.
   *
   * @param filePath directory where the avro file exists.
   * @param fileList list of files which has to be loaded.
   * @return CarbonWriterBuilder
   * @throws IOException
   */
  public CarbonWriterBuilder withAvroPath(String filePath, List<String> fileList)
      throws IOException {
    this.fileList = fileList;
    this.withAvroPath(filePath);
    return this;
  }

  /**
   * to build a {@link CarbonWriter}, which accepts row in Json format
   *
   * @return CarbonWriterBuilder
   */
  public CarbonWriterBuilder withJsonInput() {
    this.writerType = WRITER_TYPE.JSON;
    return this;
  }

  public CarbonWriterBuilder withSchemaFile(String schemaFilePath) throws IOException {
    Objects.requireNonNull(schemaFilePath, "schema file path should not be null");
    if (path == null) {
      throw new IllegalArgumentException("output path should be set before setting schema file");
    }
    carbonTable = SchemaReader.readCarbonTableFromSchema(schemaFilePath, new Configuration());
    carbonTable.getTableInfo().setTablePath(path);
    carbonTable.setTransactionalTable(false);
    List<ColumnSchema> columnSchemas =
        carbonTable.getCreateOrderColumn().stream().map(
            CarbonColumn::getColumnSchema
        ).collect(Collectors.toList());
    schema = new Schema(columnSchemas);
    return this;
  }

  /**
   * Build a {@link CarbonWriter}
   * This writer is not thread safe,
   * use withThreadSafe() configuration in multi thread environment
   *
   * @return CarbonWriter {AvroCarbonWriter/CSVCarbonWriter/JsonCarbonWriter based on Input Type }
   * @throws IOException
   * @throws InvalidLoadOptionException
   */
  public CarbonWriter build() throws IOException, InvalidLoadOptionException {
    Objects.requireNonNull(path, "path should not be null");
    if (this.writerType == null) {
      throw new RuntimeException(
          "'writerType' must be set, use withCsvInput() or withAvroInput() or withJsonInput()  "
              + "API based on input");
    }
    if (this.writtenByApp == null || this.writtenByApp.isEmpty()) {
      throw new RuntimeException(
          "'writtenBy' must be set when writing carbon files, use writtenBy() API to "
              + "set it, it can be the name of the application which is using the SDK");
    }
    if (this.schema == null) {
      throw new RuntimeException("schema should be set");
    }
    if (taskNo == null) {
      taskNo = UUID.randomUUID().toString().replace("-", "");
    }
    CarbonLoadModel loadModel = buildLoadModel(schema);
    loadModel.setSdkWriterCores(numOfThreads);
    CarbonProperties.getInstance()
        .addProperty(CarbonCommonConstants.CARBON_WRITTEN_BY_APPNAME, writtenByApp);
    if (hadoopConf == null) {
      hadoopConf = new Configuration(FileFactory.getConfiguration());
    }
    CarbonWriter carbonWriter;
    if (this.writerType == WRITER_TYPE.AVRO) {
      // AVRO records are pushed to Carbon as Object not as Strings. This was done in order to
      // handle multi level complex type support. As there are no conversion converter step is
      // removed from the load. LoadWithoutConverter flag is going to point to the Loader Builder
      // which will skip Conversion Step.
      loadModel.setLoadWithoutConverterStep(true);
      carbonWriter = new AvroCarbonWriter(loadModel,
          hadoopConf, this.avroSchema);
    } else if (this.writerType == WRITER_TYPE.JSON) {
      loadModel.setJsonFileLoad(true);
      carbonWriter = new JsonCarbonWriter(loadModel, hadoopConf);
    } else if (this.writerType == WRITER_TYPE.PARQUET) {
      loadModel.setLoadWithoutConverterStep(true);
      carbonWriter = new ParquetCarbonWriter(loadModel, hadoopConf, this.avroSchema);
    } else if (this.writerType == WRITER_TYPE.ORC) {
      carbonWriter = new ORCCarbonWriter(loadModel, hadoopConf);
    } else {
      // CSV
      CSVCarbonWriter csvCarbonWriter = new CSVCarbonWriter(loadModel, hadoopConf);
      if (!StringUtils.isEmpty(filePath) &&
          !this.options.containsKey(CarbonCommonConstants.FILE_HEADER)) {
        csvCarbonWriter.setSkipHeader(true);
      }
      carbonWriter = csvCarbonWriter;
    }
    if (!StringUtils.isEmpty(this.filePath)) {
      carbonWriter.setDataFiles(this.dataFiles);
    }
    return carbonWriter;
  }

  private void setCsvHeader(CarbonLoadModel model) {
    Field[] fields = schema.getFields();
    StringBuilder builder = new StringBuilder();
    String[] columns = new String[fields.length];
    int i = 0;
    for (Field field : fields) {
      if (null != field) {
        builder.append(field.getFieldName());
        builder.append(",");
        columns[i++] = field.getFieldName();
      }
    }
    String header = builder.toString();
    model.setCsvHeader(header.substring(0, header.length() - 1));
    model.setCsvHeaderColumns(columns);
  }

  public CarbonLoadModel buildLoadModel(Schema carbonSchema)
      throws IOException, InvalidLoadOptionException {
    if (timestamp == 0) {
      timestamp = System.currentTimeMillis();
    }
    // validate long_string_column
    Set<String> longStringColumns = new HashSet<>();
    if (options != null && options.get(CarbonCommonConstants.LONG_STRING_COLUMNS) != null) {
      String[] specifiedLongStrings =
          options.get(CarbonCommonConstants.LONG_STRING_COLUMNS).toLowerCase().split(",");
      for (String str : specifiedLongStrings) {
        longStringColumns.add(str.trim());
      }
      validateLongStringColumns(carbonSchema, longStringColumns);
    }
    // for the longstring field, change the datatype from string to varchar
    this.schema = updateSchemaFields(carbonSchema, longStringColumns);
    if (sortColumns != null && sortColumns.length != 0) {
      if (options == null || options.get("sort_scope") == null) {
        // If sort_columns are specified and sort_scope is not specified,
        // change sort scope to local_sort as now by default sort scope is no_sort.
        if (CarbonProperties.getInstance().getProperty(CarbonCommonConstants.LOAD_SORT_SCOPE)
            == null) {
          if (options == null) {
            options = new HashMap<>();
          }
          options.put("sort_scope", "local_sort");
        }
      }
    }
    if (carbonTable == null) {
      // if carbonTable is not set by user, build it using schema
      carbonTable = buildCarbonTable();
    }
    // build LoadModel
    return buildLoadModel(carbonTable, timestamp, taskNo, options);
  }

  private void validateLongStringColumns(Schema carbonSchema, Set<String> longStringColumns) {
    // long string columns must be string or varchar type
    for (Field field : carbonSchema.getFields()) {
      if (longStringColumns.contains(field.getFieldName().toLowerCase()) && (
          (field.getDataType() != DataTypes.STRING) && field.getDataType() != DataTypes.VARCHAR)) {
        throw new RuntimeException(
            "long string column : " + field.getFieldName() + " is not supported for data type: "
                + field.getDataType());
      }
    }
    // long string columns must not be present in sort columns
    if (sortColumns != null) {
      for (String col : sortColumns) {
        // already will be in lower case
        if (longStringColumns.contains(col)) {
          throw new RuntimeException(
              "long string column : " + col + "must not be present in sort columns");
        }
      }
    }
  }

  /**
   * Build a {@link CarbonTable}
   */
  private CarbonTable buildCarbonTable() {
    TableSchemaBuilder tableSchemaBuilder = TableSchema.builder();
    if (blockSize > 0) {
      tableSchemaBuilder = tableSchemaBuilder.blockSize(blockSize);
    }

    if (blockletSize > 0) {
      tableSchemaBuilder = tableSchemaBuilder.blockletSize(blockletSize);
    }

    if (pageSizeInMb > 0) {
      tableSchemaBuilder = tableSchemaBuilder.pageSizeInMb(pageSizeInMb);
    }

    tableSchemaBuilder.enableLocalDictionary(isLocalDictionaryEnabled);
    tableSchemaBuilder.localDictionaryThreshold(localDictionaryThreshold);
    List<String> sortColumnsList = new ArrayList<>();
    if (sortColumns == null) {
      // If sort columns are not specified, default set all dimensions to sort column.
      // When dimensions are default set to sort column,
      // Inverted index will be supported by default for sort columns.
      //Null check for field to handle hole in field[] ex.
      //  user passed size 4 but supplied only 2 fileds
      for (Field field : schema.getFields()) {
        if (null != field) {
          if (field.getDataType() == DataTypes.STRING ||
              field.getDataType() == DataTypes.DATE ||
              field.getDataType() == DataTypes.TIMESTAMP) {
            sortColumnsList.add(field.getFieldName());
          }
        }
      }
      sortColumns = new String[sortColumnsList.size()];
      sortColumns = sortColumnsList.toArray(sortColumns);
    } else {
      sortColumnsList = Arrays.asList(sortColumns);
    }
    ColumnSchema[] sortColumnsSchemaList = new ColumnSchema[sortColumnsList.size()];
    List<String> invertedIdxColumnsList = new ArrayList<>();
    if (null != invertedIndexColumns) {
      invertedIdxColumnsList = Arrays.asList(invertedIndexColumns);
    }
    Field[] fields = schema.getFields();
    buildTableSchema(fields, tableSchemaBuilder, sortColumnsList, sortColumnsSchemaList,
        invertedIdxColumnsList);

    tableSchemaBuilder.setSortColumns(Arrays.asList(sortColumnsSchemaList));
    String tableName;
    String dbName;
    dbName = "";
    tableName = "_tempTable-" + UUID.randomUUID().toString() + "_" + timestamp;
    TableSchema schema = tableSchemaBuilder.build();
    schema.setTableName(tableName);
    CarbonTable table =
        CarbonTable.builder().tableName(schema.getTableName()).databaseName(dbName).tablePath(path)
            .tableSchema(schema).isTransactionalTable(false).build();
    return table;
  }

  private void buildTableSchema(Field[] fields, TableSchemaBuilder tableSchemaBuilder,
      List<String> sortColumnsList, ColumnSchema[] sortColumnsSchemaList,
      List<String> invertedIdxColumnsList) {
    Set<String> uniqueFields = new HashSet<>();
    // a counter which will be used in case of complex array type. This valIndex will be assigned
    // to child of complex array type in the order val1, val2 so that each array type child is
    // differentiated to any level
    AtomicInteger valIndex = new AtomicInteger(0);
    // Check if any of the columns specified in sort columns are missing from schema.
    for (String sortColumn : sortColumnsList) {
      boolean exists = false;
      for (Field field : fields) {
        if (field.getFieldName().equalsIgnoreCase(sortColumn)) {
          exists = true;
          break;
        }
      }
      if (!exists) {
        throw new RuntimeException(
            "column: " + sortColumn + " specified in sort columns does not exist in schema");
      }
    }
    // Check if any of the columns specified in inverted index are missing from schema.
    for (String invertedIdxColumn : invertedIdxColumnsList) {
      boolean exists = false;
      for (Field field : fields) {
        if (field.getFieldName().equalsIgnoreCase(invertedIdxColumn)) {
          exists = true;
          break;
        }
      }
      if (!exists) {
        throw new RuntimeException("column: " + invertedIdxColumn
            + " specified in inverted index columns does not exist in schema");
      }
    }
    int i = 0;
    for (Field field : fields) {
      if (null != field) {
        if (!uniqueFields.add(field.getFieldName())) {
          throw new RuntimeException(
              "Duplicate column " + field.getFieldName() + " found in table schema");
        }
        int isSortColumn = sortColumnsList.indexOf(field.getFieldName());
        int isInvertedIdxColumn = invertedIdxColumnsList.indexOf(field.getFieldName());
        if (isSortColumn > -1) {
          // unsupported types for ("array", "struct", "double", "float", "decimal")
          if (field.getDataType() == DataTypes.DOUBLE || field.getDataType() == DataTypes.FLOAT
              || DataTypes.isDecimal(field.getDataType()) || field.getDataType().isComplexType()
              || field.getDataType() == DataTypes.VARCHAR
              || field.getDataType() == DataTypes.BINARY) {
            String errorMsg =
                "sort columns not supported for array, struct, map, double, float, decimal, "
                    + "varchar, binary";
            throw new RuntimeException(errorMsg);
          }
        }
        if (field.getChildren() != null && field.getChildren().size() > 0) {
          if ("ARRAY".equalsIgnoreCase(field.getDataType().getName())) {
            // Loop through the inner columns and for a StructData
            DataType complexType = DataTypes
                .createArrayType(field.getChildren().get(0).getDataType(),
                    field.getChildren().get(0).getFieldName());
            tableSchemaBuilder
                .addColumn(new StructField(field.getFieldName(), complexType, field.getChildren()),
                    valIndex, false, isInvertedIdxColumn > -1);
          } else if ("STRUCT".equalsIgnoreCase(field.getDataType().getName())) {
            // Loop through the inner columns and for a StructData
            List<StructField> structFieldsArray =
                new ArrayList<StructField>(field.getChildren().size());
            for (StructField childFld : field.getChildren()) {
              structFieldsArray
                  .add(new StructField(childFld.getFieldName(), childFld.getDataType()));
            }
            DataType complexType = DataTypes.createStructType(structFieldsArray);
            tableSchemaBuilder
                .addColumn(new StructField(field.getFieldName(), complexType, field.getChildren()),
                    valIndex, false, isInvertedIdxColumn > -1);
          } else if ("MAP".equalsIgnoreCase(field.getDataType().getName())) {
            // Loop through the inner columns for MapType
            DataType mapType = DataTypes.createMapType(((MapType) field.getDataType()).getKeyType(),
                field.getChildren().get(0).getDataType());
            tableSchemaBuilder
                .addColumn(new StructField(field.getFieldName(), mapType, field.getChildren()),
                    valIndex, false, isInvertedIdxColumn > -1);
          }
        } else {
          ColumnSchema columnSchema = tableSchemaBuilder
              .addColumn(new StructField(field.getFieldName(), field.getDataType()), valIndex,
                  isSortColumn > -1, isInvertedIdxColumn > -1);
          if (isSortColumn > -1) {
            columnSchema.setSortColumn(true);
            sortColumnsSchemaList[isSortColumn] = columnSchema;
          }
        }
      }
    }
  }

  /**
   * Build a {@link CarbonLoadModel}
   */
  private CarbonLoadModel buildLoadModel(CarbonTable table, long timestamp, String taskNo,
      Map<String, String> options) throws InvalidLoadOptionException, IOException {
    if (options == null) {
      options = new HashMap<>();
    }
    CarbonLoadModelBuilder builder = new CarbonLoadModelBuilder(table);
    CarbonLoadModel model = builder.build(options, timestamp, taskNo);
    setCsvHeader(model);
    return model;
  }

  /* loop through all the parent column and
  a) change fields name to lower case.
  this is to match with sort column case.
  b) change string fields to varchar type */
  private Schema updateSchemaFields(Schema schema, Set<String> longStringColumns) {
    if (schema == null) {
      return null;
    }
    Field[] fields = schema.getFields();
    for (int i = 0; i < fields.length; i++) {
      if (fields[i] != null) {
        if (longStringColumns != null) {
          /* Also update the string type to varchar */
          if (longStringColumns.contains(fields[i].getFieldName())) {
            fields[i].updateDataTypeToVarchar();
          }
        }
      }
    }
    return new Schema(fields);
  }

  private void updateToLoadOptions(Map.Entry<String, String> entry) {
    if (this.options == null) {
      // convert it to treeMap as keys need to be case insensitive
      this.options = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }
    // update it to load options
    this.options.put(entry.getKey(), entry.getValue());
  }

  private void withSortScope(Map.Entry<String, String> entry) {
    String sortScope = entry.getValue();
    if (sortScope != null) {
      if ((!CarbonUtil.isValidSortOption(sortScope))) {
        throw new IllegalArgumentException("Invalid Sort Scope Option: " + sortScope);
      } else if ("global_sort".equalsIgnoreCase(sortScope)) {
        throw new IllegalArgumentException("global sort is not supported");
      }
    }
    // update it to load options
    updateToLoadOptions(entry);
  }
}
