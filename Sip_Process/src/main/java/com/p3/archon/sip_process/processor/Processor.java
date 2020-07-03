package com.p3.archon.sip_process.processor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.code.externalsorting.ExternalSort;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import com.opencsv.exceptions.CsvValidationException;
import com.p3.archon.sip_process.bean.InputArgs;
import com.p3.archon.sip_process.bean.PathReportBean;
import com.p3.archon.sip_process.bean.ReportBean;
import com.p3.archon.sip_process.bean.TablewithRelationBean;
import com.p3.archon.sip_process.core.*;
import com.p3.archon.sip_process.parser.PathandQueryCreator;
import com.p3.archon.sip_process.parser.SipIntermediateJsonParser;
import com.p3.archon.sip_process.parser.SipIntermediateJsonToXmlParser;
import com.p3.archon.sip_process.utility.Utility;
import lombok.SneakyThrows;
import org.apache.log4j.Logger;

import java.io.*;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.Date;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static com.p3.archon.sip_process.constants.SipPerformanceConstant.*;
import static com.p3.archon.sip_process.utility.Utility.readLineByLineCount;
import static java.util.Map.Entry.comparingByKey;
import static java.util.stream.Collectors.toMap;

/**
 * Created by Suriyanarayanan K
 * on 22/04/20 4:04 PM.
 */


public class Processor {


    private ConnectionChecker connectionChecker = new ConnectionChecker();

    private InputArgs inputBean;
    private String mainTableName;
    private Map<String, List<String>> tablePrimaryJoinColumn;
    private Map<String, Integer> columnDataTypeMap = new LinkedHashMap<>();


    private ForkJoinPool customThreadPool;
    private ReportBean reportBean;
    private Map<String, Dictionary> tableDictionary = new LinkedHashMap<>();
    private Map<String, PrintWriter> tablePrintWriter = new LinkedHashMap<>();

    private String IDS_FILE_LOCATION = "";

    private Logger LOGGER = Logger.getLogger(this.getClass().getName());

    public Processor() {

        this.inputBean = InputArgs.builder().build();
        this.reportBean = ReportBean.builder().startTime(new Date().getTime()).build();
    }

    /**
     * Set all Config Properties Inputs variable to InputArgs Bean
     */
    public void setValuesIntoBean() {

        LOGGER.info(ALERT_INPUT);
        PropertyFileReader propertyFileReader = new PropertyFileReader("config.properties");
        String hostName = propertyFileReader.getStringValue("hostName", "");
        int portNo = propertyFileReader.getIntegerValue("port", 0);
        String databaseName = propertyFileReader.getStringValue("databaseName", "");
        String schemaName = propertyFileReader.getStringValue("schemaName", "");
        String userName = propertyFileReader.getStringValue("userName", "");
        String password = propertyFileReader.getStringValue("password", "");
        String databaseServer = propertyFileReader.getStringValue("databaseServer", "");
        String outputLocation = propertyFileReader.getStringValue("outputLocation", "");
        String mainTable = propertyFileReader.getStringValue("mainTable", "");
        String sipInputJsonLocation = propertyFileReader.getStringValue("sipInputJsonLocation", "");
        String appName = propertyFileReader.getStringValue("appName", "");
        String holdName = propertyFileReader.getStringValue("holdName", "");
        int recordPerXML = (propertyFileReader.getIntegerValue("recordPerXML", 10));
        int theadCount = (propertyFileReader.getIntegerValue("theadCount", 10));
        int pathThreadCount = (propertyFileReader.getIntegerValue("pathThreadCount", 10));
        boolean showTime = (propertyFileReader.getBooleanValue("showTime", false));
        String jobId = propertyFileReader.getStringValue("jobId", "abcd-123456");
        String mainTablePrimaryColumnFileName = propertyFileReader.getStringValue("mainTablePrimaryColumnFileName", "");


        // Record FIle Process
        boolean isRecordFileProcess = propertyFileReader.getBooleanValue("isRecordFileProcess", false);
        String fileNameList = propertyFileReader.getStringValue("fileNameList", "");
        String columndatatype = propertyFileReader.getStringValue("columnDataType", "");


        //isCleanUpData process
        boolean isCleanUpData = propertyFileReader.getBooleanValue("isCleanUpData", false);

        //isIdsFIle
        boolean isIdsFile = propertyFileReader.getBooleanValue("isIdsFile", false);
        boolean sortIdsFile = propertyFileReader.getBooleanValue("sortIdsFile", false);

        if (!new File(outputLocation).isDirectory()) {
            new File(outputLocation).mkdir();
        }
        String jobOutputLocation = outputLocation + File.separator + jobId;
        new File(jobOutputLocation).mkdir();

        inputBean = InputArgs.builder()
                .hostName(hostName)
                .portNo(String.valueOf(portNo))
                .databaseName(databaseName)
                .schemaName(schemaName)
                .user(userName)
                .pass(password)
                .databaseServer(databaseServer)
                .outputLocation(jobOutputLocation)
                .mainTable(mainTable)
                .jsonFile(sipInputJsonLocation)
                .appName(appName)
                .holdName(holdName)
                .rpx(recordPerXML)
                .threadCount(theadCount)
                .pathThreadCount(pathThreadCount)
                .showDatetime(showTime)
                .jobId(jobId)
                .isRecordFileProcess(isRecordFileProcess)
                .fileNameList(fileNameList.isEmpty() ? new ArrayList<>() : Arrays.asList(fileNameList.split("\\|")))
                .mainTablePrimaryColumnFileName(mainTablePrimaryColumnFileName)
                .columnDataType(columndatatype)
                .isCleanUpData(isCleanUpData)
                .isIdsFile(isIdsFile)
                .sortIdsFile(sortIdsFile)
                .build();
        inputBean.validateInputs();
        mainTableName = inputBean.getMainTable();

        /**
         * Custom Thread Pool Initiated with Thread Count
         *
         **/
        customThreadPool = new ForkJoinPool(inputBean.getThreadCount());
    }

    /**
     * Sip Extraction Started here
     */
    public void startExtraction() {


        PathandQueryCreator pathandQueryCreator = new PathandQueryCreator(inputBean);
        List<String> selectedTableList = pathandQueryCreator.getSelectedTableList();
        TreeMap<String, String> charReplacement = pathandQueryCreator.getCharacterReplacementMap();
        tablePrimaryJoinColumn = pathandQueryCreator.getPrimaryJoinColumn(selectedTableList);
        reportBean.setWhereCondition(pathandQueryCreator.getAllTablesWhereCondition(selectedTableList));
        if (inputBean.isIdsFile()) {
            for (String table : selectedTableList) {
                try {
                    IDS_FILE_LOCATION = inputBean.getOutputLocation();
                    if (!table.equalsIgnoreCase(mainTableName)) {
                        tablePrintWriter.put(table, new PrintWriter(IDS_FILE_LOCATION + File.separator + table + IDS));
                        tableDictionary.put(table, new Hashtable());
                    }
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
        }
        if (selectedTableList.size() == 1) {
            int fileCounter = 0;
            startExtractBlobContent(selectedTableList, pathandQueryCreator.getSingleTableBlobQueryList(mainTableName, tablePrimaryJoinColumn), reportBean.getMainTablePerformance());
            String testFileLocation = Utility.getFileName(inputBean.getOutputLocation(), RECORD_FILE, fileCounter, TXT);
            String SINGLE_TABLE_QUERY = pathandQueryCreator.getSingleTableQuery(mainTableName);

            PathReportBean mainTablePerformance = reportBean.getMainTablePerformance();
            Map<String, List<String>> tablePrimaryKey = generateTablePrimaryKeyBasedOnTableList(selectedTableList);
            List<String> headerList = startExecutingQueryToRecordFileAndReturnAsHeaderList(SINGLE_TABLE_QUERY, charReplacement, mainTablePerformance, testFileLocation, tablePrimaryKey);
            startAddValuesIntoBatchAssembler(headerList, testFileLocation, columnDataTypeMap, reportBean, pathandQueryCreator.getTablewithRelationBean());
        } else {
            Map<Integer, List<String>> pathTableList = pathandQueryCreator.startParsingAndCreationPathList(selectedTableList);
            Map<Integer, String> queryList = pathandQueryCreator.getConstructQueryList(pathTableList);
            long startConditionTime = System.currentTimeMillis();
            Connection connection = connectionChecker.checkConnection(inputBean);
            reportBean.getMainTablePerformance().setDbHitCounter(reportBean.getMainTablePerformance().getDbHitCounter() + 1);
            reportBean.getMainTablePerformance().setDbConnectionTime(reportBean.getMainTablePerformance().getDbConnectionTime() + (System.currentTimeMillis() - startConditionTime));
            List<String> mainTablePrimaryKeyColumns = getMainTablePrimaryKeyColumnsList(mainTableName);
            List<String> mainTableRowPrimaryValues = new ArrayList<>();
            LOGGER.debug("Start Pulling Main Table Values");
            if (inputBean.isRecordFileProcess()) {
                getMainTableValuesFromDebugFile(mainTableRowPrimaryValues);
            } else {
                mainTableRowPrimaryValues = getMainTablePrimaryKeyColumnsValues(pathandQueryCreator, connection, mainTablePrimaryKeyColumns, charReplacement, reportBean);
                startCreatingMainTableDebugFiles(mainTablePrimaryKeyColumns, mainTableRowPrimaryValues);
            }
            reportBean.getChildTableRecordCount().put(mainTableName, (long) mainTableRowPrimaryValues.size());
            LOGGER.debug("Main Table Values was collected");
            reportBean.setTotalSourceSipRecord(mainTableRowPrimaryValues.size());
            Map<Integer, List<String>> filePreviousLinesMaintainer = new LinkedHashMap<>();
            Map<Integer, SipIntermediateJsonParser> sipIntermediateJsonParserMap = new LinkedHashMap<>();
            if (inputBean.isRecordFileProcess()) {
                getColumnDataTypeMapFromDebugFile();
                startCreatingSipIntermediateJsonParserMap(pathandQueryCreator, selectedTableList, pathTableList, mainTablePrimaryKeyColumns, filePreviousLinesMaintainer, sipIntermediateJsonParserMap);
            } else {
                sipIntermediateJsonParserMap = getStartGeneratingRecordFileAndCreateIntermediateJsonBean(pathTableList, queryList, mainTablePrimaryKeyColumns, filePreviousLinesMaintainer, customThreadPool, charReplacement, reportBean.getPathPerformanceMap(), pathandQueryCreator);
                startGnerartingCOlumnDataTypeDebugFile();
            }
            startIterateEachMainTableRecord(mainTableRowPrimaryValues, filePreviousLinesMaintainer, sipIntermediateJsonParserMap, pathandQueryCreator.getTablewithRelationBean());
        }
        startCreatingPdiSchemaFile();
        reportBean.setEndTime(new Date().getTime());
        ReportGeneration reportGeneration = new ReportGeneration(reportBean, inputBean.getOutputLocation(), inputBean.getJobId(), inputBean.isIdsFile(), inputBean.isSortIdsFile());
        reportGeneration.generatePerformanceReport();
        reportGeneration.generateExtractionAndSummaryReport();
    }

    private void startCreatingSipIntermediateJsonParserMap(PathandQueryCreator pathandQueryCreator, List<String> selectedTableList, Map<Integer, List<String>> pathTableList, List<String> mainTablePrimaryKeyColumns, Map<Integer, List<String>> filePreviousLinesMaintainer, Map<Integer, SipIntermediateJsonParser> sipIntermediateJsonParserMap) {
        for (Map.Entry<Integer, List<String>> path : pathTableList.entrySet()) {
            String fileLocationWithName = inputBean.getFileNameList().get(path.getKey());
            List<String> headerList = pathandQueryCreator.prepareHeaderList(path.getValue());
            Map<String, Long> tableColumnCount = getTableColumnCount(headerList);
            Map<String, List<String>> tablePrimaryKey = generateTablePrimaryKeyBasedOnTableList(selectedTableList);
            Map<String, List<Integer>> tablePrimaryHeaderPosition = getTablePrimaryHeaderPositionMap(tablePrimaryKey, headerList);
            SipIntermediateJsonParser sipIntermediateJsonParser = new SipIntermediateJsonParser(fileLocationWithName, tableColumnCount, headerList, tablePrimaryHeaderPosition, path.getKey(), inputBean.getOutputLocation(), mainTablePrimaryKeyColumns.size());
            sipIntermediateJsonParserMap.put(path.getKey(), sipIntermediateJsonParser);
            filePreviousLinesMaintainer.put(path.getKey(), new ArrayList<>());
        }
    }

    private void startGnerartingCOlumnDataTypeDebugFile() {
        try {
            PrintWriter columnDataWriter = new PrintWriter(inputBean.getOutputLocation() + File.separator + "columnDataType.json");
            String json = new ObjectMapper().writeValueAsString(columnDataTypeMap);
            columnDataWriter.write(json);
            columnDataWriter.flush();
            columnDataWriter.close();
        } catch (FileNotFoundException | JsonProcessingException e) {
            LOGGER.error("While Creation of ColumnDataType : " + e.getMessage());
        }
    }

    private void getColumnDataTypeMapFromDebugFile() {
        String columnDataTypeContent = Utility.readLineByLine(inputBean.getColumnDataType());
        ObjectMapper columnDataTypeMapper = new ObjectMapper();
        try {
            columnDataTypeMap = columnDataTypeMapper.readValue(columnDataTypeContent, HashMap.class);
        } catch (IOException e) {
            LOGGER.error("Mapping the Json into Table Bean : " + e.getMessage());
        }
    }

    private void startCreatingMainTableDebugFiles(List<String> mainTablePrimaryKeyColumns, List<String> mainTableRowPrimaryValues) {
        try {
            PrintWriter mainTableWriter = new PrintWriter(inputBean.getOutputLocation() + File.separator + "MainTableValues.txt");
            mainTableWriter.append(String.join("�", mainTablePrimaryKeyColumns) + NEW_LINE);
            for (String mainTableRowPrimaryValue : mainTableRowPrimaryValues) {
                mainTableWriter.append(mainTableRowPrimaryValue + NEW_LINE);
            }
            mainTableWriter.flush();
            mainTableWriter.close();
        } catch (FileNotFoundException e) {
            LOGGER.error("While Creation of MainTableValues : " + e.getMessage());
        }
    }

    private void getMainTableValuesFromDebugFile(List<String> mainTableRowPrimaryValues) {
        CSVParser parser;
        parser = new CSVParserBuilder().withSeparator('�')
                .withIgnoreQuotations(true)
                .build();
        try {
            CSVReader csvReader = new CSVReaderBuilder(new FileReader(inputBean.getMainTablePrimaryColumnFileName()))
                    .withCSVParser(parser)
                    .withSkipLines(1).build();
            String[] line = null;
            while ((line = csvReader.readNext()) != null) {
                mainTableRowPrimaryValues.add(String.join("�", line));
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (CsvValidationException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private void startExtractBlobContent(List<String> selectedTableList, List<String> singleTableBlobQueryList, PathReportBean mainTablePerformance) {
        List<String> blobQueryList = singleTableBlobQueryList;
        if (!blobQueryList.isEmpty()) {
            startBlobExtractionProcess(blobQueryList, selectedTableList, mainTablePerformance);
        }
    }

    @SneakyThrows
    private void startAddValuesIntoBatchAssembler(List<String> headerList, String testFileLocation, Map<String, Integer> columnDataTypeMap, ReportBean reportBean, TablewithRelationBean tablewithRelationBean) {
        SipCreator singleTableSipCreator = new SipCreator(inputBean);
        CSVParser parser;
        parser = new CSVParserBuilder().withSeparator('�')
                .withFieldAsNull(CSVReaderNullFieldIndicator.BOTH).withIgnoreLeadingWhiteSpace(true)
                .withIgnoreQuotations(false).withQuoteChar('"').withStrictQuotes(false).build();
        CSVReader csvReader = new CSVReaderBuilder(new FileReader(testFileLocation))
                .withCSVParser(parser)
                .withSkipLines(1).build();

        Map<String, Long> timeMaintainer;
        String[] line;
        long rowCounter = 1;
        while ((line = csvReader.readNext()) != null) {
            timeMaintainer = new LinkedHashMap<>();
            timeMaintainer.put(SINGLE_SIP_RECORD_TIME, System.currentTimeMillis());
            reportBean.setTotalSourceSipRecord(reportBean.getTotalSourceSipRecord() + 1);
            Map<String, String> headerAndValues = new LinkedHashMap<>();
            List<String> values = Arrays.asList(line);
            for (int i = 0; i < headerList.size(); i++) {
                headerAndValues.put(headerList.get(i), values.get(i));
            }
            Map<String, String> sortedHeaderAndValues = headerAndValues.entrySet().stream().sorted(comparingByKey()).collect(toMap(e -> e.getKey(), e -> e.getValue(), (e1, e2) -> e2, LinkedHashMap::new));
            SipIntermediateJsonToXmlParser sipIntermediateJsonToXmlParser = new SipIntermediateJsonToXmlParser(inputBean, singleTableSipCreator, columnDataTypeMap, reportBean, false, tablewithRelationBean, tablePrintWriter, tableDictionary);
            timeMaintainer = sipIntermediateJsonToXmlParser.createXMlDocumentForSingleTable(sortedHeaderAndValues, timeMaintainer);
            rowCounter = updateRecordTimeWithTimeMaintainer(rowCounter, timeMaintainer, reportBean);
            sipIntermediateJsonToXmlParser.generateIdsFileCreation();
        }
        reportBean.getChildTableRecordCount().put(mainTableName, (long) (rowCounter - 1));
        reportBean.setTotalExtractedSipRecord((rowCounter - 1));
        singleTableSipCreator.getBatchAssembler().end();
    }

    private void startCreatingPdiSchemaFile() {
        PdiSchemaGeneratorRussianDoll pdiSchemaGeneratorRussianDoll = new PdiSchemaGeneratorRussianDoll(inputBean, columnDataTypeMap);
        boolean creationStatus = pdiSchemaGeneratorRussianDoll.startPdiSchemaCreation();
        if (inputBean.isCleanUpData() && creationStatus)
            deleteUnwantedFiles();
    }

    private void startIterateEachMainTableRecord(List<String> mainTableRowPrimaryValues, Map<Integer, List<String>> filePreviousLinesMaintainer, Map<Integer, SipIntermediateJsonParser> sipIntermediateJsonParserMap, TablewithRelationBean
            tablewithRelationBean
    ) {
        SipCreator sipCreator = new SipCreator(inputBean);
        SipIntermediateJsonToXmlParser sipIntermediateJsonToXmlParser = new SipIntermediateJsonToXmlParser(inputBean, sipCreator, columnDataTypeMap, reportBean, false, tablewithRelationBean, tablePrintWriter, tableDictionary);
        long rowCounter = 1;
        try {
            Map<String, Long> timeMaintainer = null;
            List<String> duplicateRowFinderInMainTable = new ArrayList<>();
            boolean isAlreadyValuesContains;
            for (String mainTableKeyRowsValue : mainTableRowPrimaryValues) {
                if (mainTableKeyRowsValue.equalsIgnoreCase("884061910")) {
                    System.out.println("Main table value");
                }
                if (duplicateRowFinderInMainTable.contains(mainTableKeyRowsValue)) {
                    isAlreadyValuesContains = true;
                } else {
                    isAlreadyValuesContains = false;
                    duplicateRowFinderInMainTable.add(mainTableKeyRowsValue);
                }
                if (!isAlreadyValuesContains) {
                    timeMaintainer = new LinkedHashMap<>();
                    timeMaintainer.put(SINGLE_SIP_RECORD_TIME, System.currentTimeMillis());
                    LOGGER.fatal(rowCounter + " : Row Start Processing");
                    boolean finalIsAlreadyValuesContains = isAlreadyValuesContains;
                    customThreadPool.submit(() -> {
                        sipIntermediateJsonParserMap.entrySet().parallelStream().forEach(
                                sipIntermediateJsonParserEntry -> {
                                    startGeneratingIntermediateJsonBasedOnMainTableValues(filePreviousLinesMaintainer, sipIntermediateJsonParserMap.get(sipIntermediateJsonParserEntry.getKey()), mainTableKeyRowsValue, sipIntermediateJsonParserEntry.getKey(), finalIsAlreadyValuesContains);
                                });
                    }).get();
                    startAddingRecordIntoBatchAssembler(sipIntermediateJsonToXmlParser, timeMaintainer);
                    rowCounter = updateRecordTimeWithTimeMaintainer(rowCounter, timeMaintainer, reportBean);
                }
            }
            sipIntermediateJsonToXmlParser.generateIdsFileCreation();
            for (Map.Entry<Integer, SipIntermediateJsonParser> sipIntermediateJsonParserEntry : sipIntermediateJsonParserMap.entrySet()) {
                sipIntermediateJsonParserEntry.getValue().closeCSVReader();
            }
            sipCreator.getBatchAssembler().end();

            File[] idsFileArray = new File(IDS_FILE_LOCATION).listFiles((dir, name) -> name.toLowerCase().endsWith(".ids"));
            if (inputBean.isIdsFile() && inputBean.isSortIdsFile()) {
                long startDuplicateRemovalTime = System.currentTimeMillis();
                duplicateIdsFileOperation(idsFileArray);
                reportBean.setIdsFileSortTime(reportBean.getIdsFileSortTime() + (System.currentTimeMillis() - startDuplicateRemovalTime));
            }
            reportBean.getChildTableRecordCount().putAll(calculatingChildTableCount(idsFileArray));
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.error("Error :" + e.getMessage());
            LOGGER.error("Error :" + rowCounter);
        }
        reportBean.setTotalExtractedSipRecord((rowCounter - 1));
        LOGGER.debug("Total No.of.Records Processed :" + (rowCounter - 1));
    }


    private Map<String, Long> calculatingChildTableCount(File[] idsFileArray) {
        long childTableRecordCountingTime = System.currentTimeMillis();
        Map<String, Long> childTableCount = new LinkedHashMap<>();
        for (File file : idsFileArray) {
            childTableCount.put(file.getName().split("\\.")[0], readLineByLineCount(file.getAbsolutePath()));
        }
        reportBean.setIdsFileCountTime(System.currentTimeMillis() - childTableRecordCountingTime);
        return childTableCount;
    }

    private void duplicateIdsFileOperation(File[] idsFileArray) {
        for (File file : idsFileArray) {
            try {
                List<File> l = ExternalSort.sortInBatch(file);
                ExternalSort.mergeSortedFiles(l, file, ExternalSort.defaultcomparator, true);
            } catch (IOException e) {
                LOGGER.error("While Removing the duplicate in the file " + file.getName());
                LOGGER.error("While Removing the duplicate Exception " + e.getMessage());
            }
        }
    }

    private long updateRecordTimeWithTimeMaintainer(long rowCounter, Map<String, Long> timeMaintainer, ReportBean reportBean) {

        reportBean.setWriteSipRecordTIme(reportBean.getWriteSipRecordTIme() + timeMaintainer.get(SINGLE_SIP_RECORD_TIME));
        reportBean.setTotalBatchAssemblerTime(reportBean.getTotalBatchAssemblerTime() + timeMaintainer.get(BATCH_ASSEMBLER_TIME));
        LOGGER.fatal(rowCounter + " : Row Processed");
        if (rowCounter % 5000 == 0) {
            LOGGER.debug(Utility.LoggerPrintFor10000Line(rowCounter));
        }
        rowCounter++;
        return rowCounter;
    }

    /**
     * Responsible for merge all the intermediate json , create sip file and delete unwanted files.
     *
     * @return
     */
    private Map<String, Long> startAddingRecordIntoBatchAssembler(SipIntermediateJsonToXmlParser sipIntermediateJsonToXmlParser, Map<String, Long> timeMaintainer) {

        sipIntermediateJsonToXmlParser.joinAllJson();
        Map<String, Long> batchAssemblerTiming = sipIntermediateJsonToXmlParser.createXMlDocument(timeMaintainer);
        return batchAssemblerTiming;
    }

    private void startGeneratingIntermediateJsonBasedOnMainTableValues(Map<Integer, List<String>> filePreviousLinesMaintainer, SipIntermediateJsonParser sipIntermediateJsonParser, String mainTableKeyRowsValue, Integer key, boolean isAlreadyValuesContains) {
        List<String> endLineCount = sipIntermediateJsonParser.startParsing(mainTableKeyRowsValue, filePreviousLinesMaintainer.get(key), isAlreadyValuesContains);
        filePreviousLinesMaintainer.put(key, endLineCount);
    }


    private Map<Integer, SipIntermediateJsonParser> getStartGeneratingRecordFileAndCreateIntermediateJsonBean(Map<Integer, List<String>> pathTableList, Map<Integer, String> queryList, List<String> mainTablePrimaryKeyColumns, Map<Integer, List<String>> filePreviousLinesMaintainer, ForkJoinPool customThreadPool, TreeMap<String, String> charReplacement, Map<Integer, PathReportBean> pathPerformanceMap, PathandQueryCreator pathandQueryCreator) {


        Map<Integer, SipIntermediateJsonParser> sipIntermediateJsonParserMap = new LinkedHashMap<>();
        String ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN = " ORDER BY " + String.join(",", mainTablePrimaryKeyColumns);
        try {
            customThreadPool.submit(() -> {
                queryList.entrySet().parallelStream().forEach(
                        query -> {
                            pathPerformanceMap.put(query.getKey(), PathReportBean.builder().build());
                            startExtractBlobContent(pathTableList.get(query.getKey()), pathandQueryCreator.getBlobQueryList(query.getKey(), tablePrimaryJoinColumn), pathPerformanceMap.get(query.getKey()));
                            startGeneratingRecordFile(pathTableList, filePreviousLinesMaintainer, sipIntermediateJsonParserMap, ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN, query, mainTablePrimaryKeyColumns, charReplacement, pathPerformanceMap.get(query.getKey()));
                        });
            }).get();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        return sipIntermediateJsonParserMap;
    }


    private void startBlobExtractionProcess(List<String> blobQueryList, List<String> tableList, PathReportBean pathReportBean) {
        try {
            long startConnectionTime = System.currentTimeMillis();
            Connection connection = connectionChecker.checkConnection(inputBean);
            Statement statement = connection.createStatement();
            pathReportBean.setDbConnectionTime(pathReportBean.getDbConnectionTime() + (System.currentTimeMillis() - startConnectionTime));
            pathReportBean.setDbHitCounter(pathReportBean.getDbHitCounter() + 1);
            for (String BLOB_QUERY : blobQueryList) {
                startConnectionTime = System.currentTimeMillis();
                ResultSet blobQueryResultSet = statement.executeQuery(BLOB_QUERY);
                pathReportBean.setDbConnectionTime(pathReportBean.getDbConnectionTime() + (System.currentTimeMillis() - startConnectionTime));
                ResultSetMetaData blobQueryResultSetMetadata = blobQueryResultSet.getMetaData();
                RowObjectCreator blobExtractionCreator = new RowObjectCreator(blobQueryResultSetMetadata, inputBean.getOutputLocation(), new TreeMap<>(), inputBean.isShowDatetime());
                Map<String, List<String>> tablePrimaryKey = generateTablePrimaryKeyBasedOnTableList(tableList);
                blobExtractionCreator.setTablePrimaryKeyColumns(tablePrimaryKey);
                long resultSetTime = System.currentTimeMillis();
                while (blobQueryResultSet.next()) {
                    pathReportBean.setResultSetTime(pathReportBean.getResultSetTime() + (System.currentTimeMillis() - resultSetTime));
                    blobExtractionCreator.generateBlobFiles(blobQueryResultSet);
                    resultSetTime = System.currentTimeMillis();
                }
                updateCounterAndTimeIntoPerformance(pathReportBean, blobExtractionCreator);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateCounterAndTimeIntoPerformance(PathReportBean pathReportBean, RowObjectCreator blobExtractionCreator) {
        pathReportBean.setNormalCounter(pathReportBean.getNormalCounter() + blobExtractionCreator.getStringRecordCounter());
        pathReportBean.setBlobCounter(pathReportBean.getBlobCounter() + blobExtractionCreator.getBlobCounter());
        pathReportBean.setClobCounter(pathReportBean.getClobCounter() + blobExtractionCreator.getClobCounter());
        pathReportBean.setNormalTime(pathReportBean.getNormalTime() + blobExtractionCreator.getTotalStringRecordWriteTime());
        pathReportBean.setBlobTime(pathReportBean.getBlobTime() + blobExtractionCreator.getTotalBlobWriteTime());
        pathReportBean.setClobTime(pathReportBean.getClobTime() + blobExtractionCreator.getTotalClobWriteTime());
    }


    private void startGeneratingRecordFile(Map<Integer, List<String>> pathTableList, Map<Integer, List<String>> filePreviousLinesMaintainer, Map<Integer, SipIntermediateJsonParser> intermediateJsonBeanMap, String ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN, Map.Entry<Integer, String> query, List<String> mainTablePrimaryKeyColumns, TreeMap<String, String> charReplacement, PathReportBean pathReportBean) {
        LOGGER.info(query.getKey() + ": Path Record File Created Successfully Started");
        intermediateJsonBeanMap.put(query.getKey(), runQueryAndCreateIntermediateJson(query.getKey(), query.getValue() + ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN, pathTableList.get(query.getKey()), mainTablePrimaryKeyColumns, charReplacement, pathReportBean));
        filePreviousLinesMaintainer.put(query.getKey(), new ArrayList<>());
        LOGGER.info(query.getKey() + ": Path Record File Created Successfully Completed");
    }

    private List<String> getMainTablePrimaryKeyColumnsValues(PathandQueryCreator pathandQueryCreator, Connection
            connection, List<String> mainTablePrimaryKeyColumns, TreeMap<String, String> charReplacement, ReportBean reportBean) {
        List<String> mainTableTableRowPrimaryValues = new ArrayList<>();
        Statement statement = null;
        ResultSet mainTableResultSet = null;
        try {
            long connectionTime = System.currentTimeMillis();
            statement = connection.createStatement();
            String ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN = " ORDER BY " + String.join(",", mainTablePrimaryKeyColumns);
            String QUERY_STRING = " SELECT " + Utility.getColumnAsQueryFramer(mainTablePrimaryKeyColumns) + " from " +
                    inputBean.getSchemaName() + "." + mainTableName + pathandQueryCreator.getMainTableFilterQuery() + ORDER_BY_MAIN_TABLE_PRIMARY_COLUMN;
            LOGGER.debug("Main Table Query :" + QUERY_STRING);
            mainTableResultSet = statement.executeQuery(QUERY_STRING);
            reportBean.getMainTablePerformance().setDbConnectionTime(reportBean.getMainTablePerformance().getDbConnectionTime() + (System.currentTimeMillis() - connectionTime));
            ResultSetMetaData mainTableResultSetMetaData = mainTableResultSet.getMetaData();
            RowObjectCreator mainTableRowObjectCreator = new RowObjectCreator(mainTableResultSetMetaData, inputBean.getOutputLocation(), charReplacement, inputBean.isShowDatetime());
            long resultSetTime = System.currentTimeMillis();
            int count = 0;
            while (mainTableResultSet.next()) {
                reportBean.getMainTablePerformance().setResultSetTime(reportBean.getMainTablePerformance().getResultSetTime() + (System.currentTimeMillis() - resultSetTime));
                List<String> rowObject = mainTableRowObjectCreator.getRowObjectList(mainTableResultSet);
                mainTableTableRowPrimaryValues.add(String.join("�", rowObject));
                resultSetTime = System.currentTimeMillis();
                if (count % 5000 == 0) {
                    LOGGER.debug("Main Tables values pulled upto :" + count);
                }
                count++;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            try {
                statement.close();
                mainTableResultSet.close();
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return mainTableTableRowPrimaryValues;
    }

    private List<String> getMainTablePrimaryKeyColumnsList(String mainTable) {
        List<String> mainTablePrimaryKeyColumns = new ArrayList<>();
        Map<String, List<String>> tablePrimaryKey = generateTablePrimaryKeyBasedOnTableList(Arrays.asList(new String[]{inputBean.getSchemaName() + "." + mainTable}));
        mainTablePrimaryKeyColumns.addAll(tablePrimaryKey.get(mainTable));
        return mainTablePrimaryKeyColumns;
    }


    /**
     * Responsible for run the query and parse the data .
     */
    private SipIntermediateJsonParser runQueryAndCreateIntermediateJson(int fileCounter, String
            query, List<String> pathTableList, List<String> mainTablePrimaryKeyColumns, TreeMap<String, String> charReplacement, PathReportBean pathReportBean) {
        LOGGER.fatal("QUERY  : " + query);
        if (pathReportBean == null) {
            pathReportBean = PathReportBean.builder().build();
        }
        String testFileLocation = Utility.getFileName(inputBean.getOutputLocation(), RECORD_FILE, fileCounter, TXT);
        Map<String, Long> tableColumnCount;
        Map<String, List<Integer>> tablePrimaryHeaderPosition;
        Map<String, List<String>> tablePrimaryKey = generateTablePrimaryKeyBasedOnTableList(pathTableList);
        List<String> headerList = startExecutingQueryToRecordFileAndReturnAsHeaderList(query, charReplacement, pathReportBean, testFileLocation, tablePrimaryKey);
        tableColumnCount = getTableColumnCount(headerList);
        tablePrimaryHeaderPosition = getTablePrimaryHeaderPositionMap(tablePrimaryKey, headerList);
        SipIntermediateJsonParser sipIntermediateJsonParser = new SipIntermediateJsonParser(testFileLocation, tableColumnCount, headerList, tablePrimaryHeaderPosition, fileCounter, inputBean.getOutputLocation(), mainTablePrimaryKeyColumns.size());
        return sipIntermediateJsonParser;
    }

    private List<String> startExecutingQueryToRecordFileAndReturnAsHeaderList(String query, TreeMap<String, String> charReplacement, PathReportBean pathReportBean, String testFileLocation, Map<String, List<String>> tablePrimaryKey) {
        List<String> headerList = null;
        try (PrintWriter recordFileWriter = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(testFileLocation)), "UTF-8"));) {
            long startConnectionTime = System.currentTimeMillis();
            Connection connection = connectionChecker.checkConnection(inputBean);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(query);
            pathReportBean.setDbConnectionTime(pathReportBean.getDbConnectionTime() + (System.currentTimeMillis() - startConnectionTime));
            pathReportBean.setDbHitCounter(pathReportBean.getDbHitCounter() + 1);
            ResultSetMetaData resultSetMetaData = resultSet.getMetaData();
            headerList = getHeaderList(resultSetMetaData);
            recordFileWriter.append(String.join("�", headerList) + "\n");
            RowObjectCreator rowObjectCreator = new RowObjectCreator(resultSetMetaData, inputBean.getOutputLocation(), charReplacement, inputBean.isShowDatetime());
            rowObjectCreator.setTablePrimaryKeyColumns(tablePrimaryKey);
            while (getNext(resultSet, pathReportBean)) {
                List<String> rowObject = rowObjectCreator.getRowObjectList(resultSet);
                recordFileWriter.append(String.join("�", rowObject) + "\n");
                recordFileWriter.flush();
            }
            updateCounterAndTimeIntoPerformance(pathReportBean, rowObjectCreator);
            Utility.endWriter(recordFileWriter);
        } catch (SQLException | FileNotFoundException | UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return headerList;
    }

    private Map<String, List<Integer>> getTablePrimaryHeaderPositionMap
            (Map<String, List<String>> tablePrimaryKey, List<String> headerList) {
        Map<String, List<Integer>> tablePrimaryHeaderPosition = new LinkedHashMap<>();
        tablePrimaryKey.entrySet().forEach(
                keyValue -> {
                    List<Integer> positions = new ArrayList<>();
                    keyValue.getValue().forEach(
                            column -> {
                                positions.add(headerList.indexOf(column));
                            }
                    );
                    Collections.sort(positions);
                    tablePrimaryHeaderPosition.put(keyValue.getKey(), positions);
                }
        );
        return tablePrimaryHeaderPosition;
    }


    private Map<String, Long> getTableColumnCount(List<String> headerList) {
        Map<String, Long> tableColumnCount = new LinkedHashMap<>();
        for (String table : headerList.stream().map(header -> header.split("\\.")[0]).distinct().collect(Collectors.toList())) {
            tableColumnCount.put(table, headerList.stream().filter(header -> header.startsWith(table + ".")).count());
        }
        return tableColumnCount;
    }


    private List<String> getHeaderList(ResultSetMetaData resultSetMetaData) throws SQLException {
        List<String> headerList = new ArrayList<>();
        for (int i = 1; i <= resultSetMetaData.getColumnCount(); i++) {
            columnDataTypeMap.put(resultSetMetaData.getColumnLabel(i).trim(), resultSetMetaData.getColumnType(i));
            headerList.add(resultSetMetaData.getColumnLabel(i).trim());
        }
        return headerList;
    }

    private Boolean getNext(ResultSet rs, PathReportBean pathReportBean) throws SQLException {
        boolean returnValue;
        long startTime = System.currentTimeMillis();
        returnValue = rs.next();
        pathReportBean.setResultSetTime(pathReportBean.getResultSetTime() + (System.currentTimeMillis() - startTime));
        return returnValue;
    }


    private Map<String, List<String>> generateTablePrimaryKeyBasedOnTableList(List<String> tableList) {
        Map<String, List<String>> tableListPrimaryKeys = new LinkedHashMap<>();
        for (String table : tableList) {
            if (table.contains(".")) {
                tableListPrimaryKeys.put(table.split("\\.")[1], tablePrimaryJoinColumn.get(table.split("\\.")[1]));
            } else {
                tableListPrimaryKeys.put(table, tablePrimaryJoinColumn.get(table));
            }
        }
        return tableListPrimaryKeys;
    }

    public void deleteUnwantedFiles() {
        File outputFolder = new File(inputBean.getOutputLocation());
        File[] filesList = outputFolder.listFiles((dir, name) -> name.toLowerCase().endsWith("." + JSON) || name.toLowerCase().endsWith("." + TXT));
        Path rootPath = Paths.get(inputBean.getOutputLocation() + File.separator + ATTACHMENT_FOLDER);
        try {
            Files.walk(rootPath, FileVisitOption.FOLLOW_LINKS)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (File deleteFile : filesList) {
            deleteFile.delete();
        }
    }
}
