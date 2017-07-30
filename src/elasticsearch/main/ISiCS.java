package elasticsearch.main;

import elasticsearch.document.Document;
import elasticsearch.document.Method;
import elasticsearch.settings.IndexSettings;
import elasticsearch.settings.Settings;
import elasticsearch.settings.TokenizerMode;
import org.elasticsearch.client.Client;

import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.FSDirectory;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class ISiCS {

    private ESConnector es;
    private String server;
    private String index;
    private String type;
    private String inputFolder;
    private String normMode;
    private TokenizerMode modes = new TokenizerMode();
    private int ngramSize = 4;
    private boolean isNgram = false;
    private boolean isPrint = false;
    private nGramGenerator ngen;
    private Options options = new Options();
    private boolean isDFS = true;
    private String outputFolder = "";
    private boolean writeToFile = false;
    private String[] extensions = { "java" };
    private int resultOffset = 0;
    private int resultsSize = 10;

    public ISiCS() {

    }

    public ISiCS(
            String server,
            String index,
            String type,
            String inputFolder,
            String normMode,
            TokenizerMode modes,
            boolean isNgram,
            int ngramSize,
            boolean isPrint,
            boolean isDFS,
            String outputFolder,
            boolean writeToFile,
            String[] extensions,
            int resultOffset,
            int resultsSize) {
        // setup all parameter values
        this.server = server;
        this.index = index;
        this.type = type;
        this.inputFolder = inputFolder;
        this.normMode = normMode;
        this.modes = modes;
        this.isNgram = isNgram;
        this.ngramSize = ngramSize;
        this.isPrint = isPrint;
        this.isDFS = isDFS;
        this.outputFolder = outputFolder;
        this.writeToFile = writeToFile;
        this.extensions = extensions;
        this.resultOffset = resultOffset;
        this.resultsSize = resultsSize;
    }

    public void execute(String command) {

        // create a connector
        es = new ESConnector(server);

        // initialise the n-gram generator
        ngen = new nGramGenerator(ngramSize);

        String indexSettings = IndexSettings.DFR.getIndexSettings(
                IndexSettings.DFR.bmIF,
                IndexSettings.DFR.aeL,
                IndexSettings.DFR.normH1);
        String mappingStr = IndexSettings.DFR.mappingStr;

        try {
            Client isicsClient = es.startup();
            if (isicsClient != null) {
                if (command.toLowerCase().equals("index")) {

                    createIndex(indexSettings, mappingStr);

                    boolean status = insert(inputFolder, Settings.IndexingMode.SEQUENTIAL);

                    if (status) {
                        // if ok, refresh the index, then search
                        es.refresh(index);
                        System.out.println("Successfully creating index.");
                    } else {
                        System.out.println("Indexing error: please check!");
                    }

                } else if (command.toLowerCase().equals("search")) {
                    search(inputFolder, resultOffset, resultsSize);
                }
                es.shutdown();
            } else {
                System.out.println("ERROR: cannot create Elasticsearch client ... ");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean createIndex(String indexSettings, String mappingStr) {

        if (isPrint) System.out.println("INDEX," + index);

        // delete the index if it exists
        if (es.doesIndexExist(index)) {
            es.deleteIndex(index);
        }
        // create index
        boolean isCreated = es.createIndex(index, type, indexSettings, mappingStr);
        if (!isCreated) {
            System.err.println("Cannot create index: " + index);
        }
        return isCreated;
    }

    protected EvalResult runExperiment(String hostname, String indexName, String typeName, String inputDir
            , String[] normModes, int[] ngramSizes, boolean useNgram
            , boolean useDFS, String outputDir, boolean writeToOutputFile, String indexSettings
            , String mappingStr, boolean printLog, boolean isDeleteIndex, String errMeasure) {

        server = hostname;
        type = typeName;
        inputFolder = inputDir;
        isNgram = useNgram;
        isDFS = useDFS;
        outputFolder = outputDir;
        writeToFile = writeToOutputFile;
        isPrint = printLog;

        // create a connector
        es = new ESConnector(server);

        EvalResult bestResult = new EvalResult();

        try {

            es.startup();
            for (String normMode : normModes) {

                // reset the modes before setting it again
                modes.reset();

                // set the normalisation + tokenization mode
                TokenizerMode tknzMode = new TokenizerMode();
                modes = tknzMode.setTokenizerMode(normMode.toLowerCase().toCharArray());

                for (int ngramSize : ngramSizes) {

                    index = indexName + "_" + normMode + "_" + ngramSize;
                    if (isPrint) System.out.println("INDEX," + index);

                    // delete the index if it exists
                    if (es.doesIndexExist(index)) {
                        es.deleteIndex(index);
                    }

                    // create index
                    if (!es.createIndex(index, type, indexSettings, mappingStr)) {
                        System.err.println("Cannot create index: " + index);
                        System.exit(-1);
                    }

                    // initialise the ngram generator
                    ngen = new nGramGenerator(ngramSize);
                    boolean insertStatus = insert(inputFolder, Settings.IndexingMode.SEQUENTIAL);

                    if (insertStatus) {

                        // if ok, refresh the index, then search
                        es.refresh(index);
                        EvalResult result = evaluate(index, outputDir, errMeasure, isPrint);

                        if (result.getValue() > bestResult.getValue()) {
                            bestResult = result;
                        }

                    } else {
                        System.out.println("Indexing error: please check!");
                    }

                    // delete index
                    if (isDeleteIndex) {
                        if (!es.deleteIndex(index)) {
                            System.err.println("Cannot delete index: " + index);
                            System.exit(-1);
                        }
                    }
                }
            }
            es.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return bestResult;
    }

    @SuppressWarnings("unchecked")
    private boolean insert(String inputFolder, int indexMode) throws Exception {
        boolean isIndexed = true;
        ArrayList<Document> docArray = new ArrayList<>();
        File folder = new File(inputFolder);

        List<File> listOfFiles = (List<File>) FileUtils.listFiles(folder, extensions, true);

        // counter for id
        int count = 0;

        for (File file : listOfFiles) {
            String filePath = file.getAbsolutePath().replace(Experiment.prefixToRemove, "");
            if (isPrint)
                System.out.println(count + ": " + filePath);
            // parse each file into method (if possible)
            MethodParser methodParser = new MethodParser(file.getAbsolutePath(), Experiment.prefixToRemove);
            ArrayList<Method> methodList;

            try {
                methodList = methodParser.parseMethods();

                // check if there's a method
                if (methodList.size() > 0) {

                    for (Method method : methodList) {

                        // Create Document object and put in an array list
                        String src = tokenize(method.getSrc());

                        // Use file name as id
                        Document d = new Document(String.valueOf(count),
                                filePath + "_" + method.getName(),
                                src);

                        // add document to array
                        docArray.add(d);
                        count++;
                    }
                } else {

                    // cannot parse, use the whole file
                    String src = tokenize(file);

                    // Use file name as id
                    Document d = new Document(String.valueOf(count), filePath + "_raw", src);

                    // add document to array
                    docArray.add(d);
                    count++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (indexMode == Settings.IndexingMode.SEQUENTIAL) {
                try {
                    isIndexed = es.sequentialInsert(index, type, docArray);
                } catch (Exception e) {
                    System.out.print(e.getMessage());
                    System.exit(0);
                }

                // something wrong with indexing, return false
                if (!isIndexed)
                    throw new Exception("Cannot insert docId " + count + " in sequential mode");
                else {
                    // reset the array list
                    docArray.clear();
                }
            }
            // index every 100 docs
            // doing indexing (can choose between bulk/sequential)
            else if (indexMode == Settings.IndexingMode.BULK) {
                if (docArray.size() >= Settings.BULK_SIZE) {
                    isIndexed = es.bulkInsert(index, type, docArray);

                    if (!isIndexed)
                        throw new Exception("Cannot bulk insert documents");
                    else {
                        // reset the array list
                        docArray.clear();
                    }
                }
            }
        }

        // the last batch
        if (indexMode == Settings.IndexingMode.BULK && docArray.size() != 0) {
            isIndexed = es.bulkInsert(index, type, docArray);

            if (!isIndexed)
                throw new Exception("Cannot bulk insert documents");
            else {
                // reset the array list
                docArray.clear();
            }
        }

        // successfully indexed, return true
        System.out.println("Successfully indexed documents.");

        return true;
    }

    private String tokenize(File file) throws Exception {
        String src = "";
        JavaTokenizer tokenizer = new JavaTokenizer(modes);

        if (modes.getEscape() == Settings.Normalize.ESCAPE_ON) {
            try (BufferedReader br = new BufferedReader(new FileReader(file.getAbsolutePath()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    ArrayList<String> tokens = tokenizer.noNormalizeAToken(escapeString(line).trim());
                    src += printArray(tokens, false);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            // generate tokens
            ArrayList<String> tokens = tokenizer.getTokensFromFile(file.getAbsolutePath());
            src = printArray(tokens, false);
            // enter ngram mode
            if (isNgram) {
                src = printArray(ngen.generateNGramsFromJavaTokens(tokens), false);
            }
        }
        return src;
    }

    private String tokenize(String sourcecode) throws Exception {
        String src;
        JavaTokenizer tokenizer = new JavaTokenizer(modes);

        // generate tokens
        ArrayList<String> tokens = tokenizer.getTokensFromString(sourcecode);
        src = printArray(tokens, false);
        // enter ngram mode
        if (isNgram) {
            src = printArray(ngen.generateNGramsFromJavaTokens(tokens), false);
        }
        return src;
    }

    public ArrayList<String> tokenizeStringToArray(String sourcecode) throws Exception {
        String src;
        JavaTokenizer tokenizer = new JavaTokenizer(modes);

        // generate tokens
        ArrayList<String> tokens = tokenizer.getTokensFromString(sourcecode);

        // enter ngram mode
        if (isNgram) {
            tokens = ngen.generateNGramsFromJavaTokens(tokens);
        }
        return tokens;
    }

    /* copied from http://stackoverflow.com/questions/4640034/calculating-all-of-the-subsets-of-a-set-of-numbers */
    public Set<Set<String>> powerSet(Set<String> originalSet) {
        Set<Set<String>> sets = new HashSet<Set<String>>();
        if (originalSet.isEmpty()) {
            sets.add(new HashSet<String>());
            return sets;
        }
        List<String> list = new ArrayList<String>(originalSet);
        String head = list.get(0);

        Set<String> rest = new HashSet<String>(list.subList(1, list.size()));
        for (Set<String> set : powerSet(rest)) {
            Set<String> newSet = new HashSet<String>();
            newSet.add(head);
            newSet.addAll(set);
            // TODO: do we need to write to a file here?
//			Experiment.writeToFile(outputFolder
//					, "queries.txt"
//					, newSet.toString().replace("[","").replace(",","").replace("]","\n")
//					, true);
            // System.out.println(newSet);
            sets.add(newSet);
            sets.add(set);
        }

        return sets;
    }

    public ArrayList<String> generate2NQuery(ArrayList<String> tokens) {
        ArrayList<String> querySet = new ArrayList<String>();

        // create a set to store query terms (removing duplicated terms)
        Set<String> queryTerms = new HashSet<String>(tokens);

        if (isPrint)
            System.out.println("Size of term (set-based): " + queryTerms.size());

        Set<Set<String>> possibleQueries = powerSet(queryTerms);

        if (isPrint)
            System.out.println("Size of sub queries: " + possibleQueries.size());

        for (Set<String> query: possibleQueries) {
            String queryStr = "";
            for (String t: query) {
                queryStr += t + " ";
            }
            querySet.add(queryStr.trim());
        }

        return querySet;
    }

    private String performSearch(String query, String outputFileLocation, String fileName, String output) {

        String outToFile = output;
        outToFile += query + ",";

        // search for results
        ArrayList<Document> results = es.search(index, type, query, isPrint, isDFS, 0, 10);
        int resultCount = 0;

        for (Document d : results) {
            if (resultCount>0)
                outToFile += ","; // add comma in between

            outToFile += d.getFile();
            resultCount++;
        }

        outToFile += "\n";

        Experiment.writeToFile(outputFileLocation, fileName, outToFile, false);
        System.out.println("Searching done. See output at " + outputFileLocation + "/" + fileName);

        return outputFileLocation + "/" + fileName;
    }

    /***
     * Read idf of each term in the query directly from Lucene index
     * @param terms query containing search terms
     * @param selectedSize size of the selected terms
     * @return selected top-selectedSize terms
     */
    private String getSelectedTerms(String indexName, String terms, int selectedSize) {
        String indexFile = "/Users/Chaiyong/elasticsearch-2.2.0/data/stackoverflow/nodes/0/indices/"
                + indexName + "/0/index";
        String selectedTerms = "";
        try {
            IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(indexFile)));

            String[] termsArr = terms.split(" ");

            // TODO: fix this constant values!!

            SelectedTerm firstMinTerm = new SelectedTerm("x", 9999999);
            SelectedTerm secondMinTerm = new SelectedTerm("x", 9999999);
            SelectedTerm thirdMinTerm = new SelectedTerm("x", 9999999);
            SelectedTerm fourthMinTerm = new SelectedTerm("x", 9999999);
            SelectedTerm fifthMinTerm = new SelectedTerm("x", 9999999);

            for (String term: termsArr) {
                // TODO: get rid of the blank term (why it's blank?)
                if (!term.equals("")) {
                    Term t = new Term("src", term);
                    int freq = reader.docFreq(t);

                    if (freq < firstMinTerm.getFrequency()) {
                        firstMinTerm.setFrequency(freq);
                        firstMinTerm.setTerm(term);
                    } else if (!term.equals(firstMinTerm.getTerm()) &&
                            freq < secondMinTerm.getFrequency()) {
                        secondMinTerm.setFrequency(freq);
                        secondMinTerm.setTerm(term);
                    } else if (!term.equals(firstMinTerm.getTerm()) &&
                            !term.equals(secondMinTerm.getTerm()) &&
                            freq < thirdMinTerm.getFrequency()) {
                        thirdMinTerm.setFrequency(freq);
                        thirdMinTerm.setTerm(term);
                    } else if (!term.equals(firstMinTerm.getTerm()) &&
                            !term.equals(secondMinTerm.getTerm()) &&
                            !term.equals(thirdMinTerm.getTerm()) &&
                            freq < fourthMinTerm.getFrequency()) {
                        fourthMinTerm.setFrequency(freq);
                        fourthMinTerm.setTerm(term);
                    } else if (!term.equals(firstMinTerm.getTerm()) &&
                            !term.equals(secondMinTerm.getTerm()) &&
                            !term.equals(thirdMinTerm.getTerm()) &&
                            !term.equals(fourthMinTerm.getTerm()) &&
                            freq < fifthMinTerm.getFrequency()) {
                        fifthMinTerm.setFrequency(freq);
                        fifthMinTerm.setTerm(term);
                    }
                }
            }

            selectedTerms = "\"" + firstMinTerm.getTerm().replace("\"", "&quot;") + "\"," + firstMinTerm.getFrequency() +
                    ",\"" + secondMinTerm.getTerm().replace("\"", "&quot;") + "\"," + secondMinTerm.getFrequency() +
                    ",\"" + thirdMinTerm.getTerm().replace("\"", "&quot;") + "\"," + thirdMinTerm.getFrequency() +
                    ",\"" + fourthMinTerm.getTerm().replace("\"", "&quot;") + "\"," + fourthMinTerm.getFrequency() +
                    ",\"" + fifthMinTerm.getTerm().replace("\"", "&quot;") + "\"," + fifthMinTerm.getFrequency();

            System.out.println(selectedTerms);

            selectedTerms = firstMinTerm.getTerm() +  " " + secondMinTerm.getTerm() + " " + thirdMinTerm.getTerm() + " " + fourthMinTerm + " " + fifthMinTerm;

        } catch (IOException e) {
            e.printStackTrace();
        }

        return selectedTerms;
    }

    /***
     * Evaluate the search results by either r-precision or mean average precision (MAP)
     * @param mode parameter settings
     * @param workingDir location of the results
     * @param errMeasure type of error measure
     * @return A pair of the best performance (either ARP or MAP) and its value
     */
    private EvalResult evaluate(String mode, String workingDir, String errMeasure, boolean isPrint) throws Exception {

        Evaluator evaluator = new Evaluator("resources/clone_clusters.csv", mode, workingDir, isPrint);
        EvalResult result = new EvalResult();

        switch (errMeasure) {
            case "arp":
                String outputFile = search(inputFolder, 0, 10);
                double arp = evaluator.evaluateARP(outputFile, 6);
                if (isPrint)
                    System.out.println("ARP: " + arp);

                // update the max ARP
                if (result.getValue() < arp) {
                    result.setValue(arp);
                    result.setSetting(outputFile);
                }

                break;
            case "map":
                int offset = 0;
                int size = 204;
                outputFile = search(inputFolder, offset, size);
                double map = evaluator.evaluateMAP(outputFile, size);
                if (isPrint)
                    System.out.println("MAP: " + map);

                // update the max MAP
                if (result.getValue() < map) {
                    result.setValue(map);
                    result.setSetting(outputFile);
                }

                break;
            default:
                System.out.println("ERROR: Invalid evaluation method.");
                break;
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private String search(String inputFolder, int offset, int size) throws Exception {

        String outToFile = "";

        DateFormat df = new SimpleDateFormat("dd-MM-yy_HH-mm-ss");
        Date dateobj = new Date();
        File outfile = new File(outputFolder + "/" + index + "_" + df.format(dateobj) + ".csv");

        // if file doesn't exists, then create it
        boolean isCreated = false;
        if (!outfile.exists()) {
            isCreated = outfile.createNewFile();
        }

        if (isCreated) {
            FileWriter fw = new FileWriter(outfile.getAbsoluteFile(), true);
            BufferedWriter bw = new BufferedWriter(fw);

            File folder = new File(inputFolder);
            List<File> listOfFiles = (List<File>) FileUtils.listFiles(folder, extensions, true);

            int count = 0;

            for (File file : listOfFiles) {
                if (isPrint)
                    System.out.println("File: " + file.getAbsolutePath());

                // reset the output buffer
                outToFile = "";

                // parse each file into method (if possible)
                MethodParser methodParser = new MethodParser(file.getAbsolutePath(), Experiment.prefixToRemove);
                ArrayList<Method> methodList;
                String query = "";

                try {
                    methodList = methodParser.parseMethods();
                    ArrayList<Document> results = new ArrayList<>();

                    // check if there's a method
                    if (methodList.size() > 0) {
                        for (Method method : methodList) {

                            // write output to file
                            outToFile += method.getFile().replace(Experiment.prefixToRemove, "") + "_"
                                    + method.getName() + "," ;

                            query = tokenize(method.getSrc());
//                            query = getSelectedTerms(index, tmpQuery, 3);

                            // search for results
                            results = es.search(index, type, query, isPrint, isDFS, offset, size);

                            int resultCount = 0;
                            for (Document d : results) {
                                if (resultCount>0)
                                    outToFile += ","; // add comma in between

                                outToFile += d.getFile();
                                resultCount++;
                            }
                            outToFile += "\n";
                        }
                    } else {
                        query = tokenize(file);

                        // search for results
                        results = es.search(index, type, query, isPrint, isDFS, offset, size);
                        outToFile += file.getAbsolutePath().replace(Experiment.prefixToRemove, "") +
                                "_noMethod" +
                                ",";
                        int resultCount = 0;
                        for (Document d : results) {
                            if (resultCount>0)
                                outToFile += ","; // add comma in between

                            outToFile += d.getFile();
                            resultCount++;
                        }
                        outToFile += "\n";
                    }

                    count++;

                } catch (Exception e) {
                    System.out.println(e.getCause());
                    System.out.println("Error: query term size exceeds 4096 (too big).");
                }

                bw.write(outToFile);
            }

            bw.close();

            System.out.println("Searching done. See output at " + outfile.getAbsolutePath());

        } else {
            throw new IOException("Cannot create the output file: " + outfile.getAbsolutePath());
        }

        return outfile.getAbsolutePath();
    }

    private int findTP(ArrayList<String> results, String query) {
        int tp = 0;
        for (String result : results) {
            if (result.contains(query)) {
                tp++;
            }
        }
        return tp;
    }

    /***
     * Copied from: http://stackoverflow.com/questions/2808535/round-a-double-to-2-decimal-places
     */
    private double round(double value, int places) {
        if (places < 0) throw new IllegalArgumentException();

        BigDecimal bd = new BigDecimal(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    private String printArray(ArrayList<String> arr, boolean pretty) {
        String s = "";
        for (String anArr : arr) {
            if (pretty && anArr.equals("\n")) {
                System.out.print(anArr);
                continue;
            }
            s += anArr + " ";
        }
        return s;
    }

    private String escapeString(String input) {
        String output = "";
        output += input.replace("\\", "\\\\").replace("\"", "\\\"").replace("/", "\\/").replace("\b", "\\b")
                .replace("\f", "\\f").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return output;
    }

    public class SelectedTerm {
        private String term;
        private int frequency;

        public SelectedTerm(String term, int frequency) {
            this.term = term;
            this.frequency = frequency;
        }

        public String getTerm() {
            return term;
        }

        public void setTerm(String term) {
            this.term = term;
        }

        public int getFrequency() {
            return frequency;
        }

        public void setFrequency(int frequency) {
            this.frequency = frequency;
        }
    }

    public void setIsPrint(boolean isPrint) {
        this.isPrint = isPrint;
    }

    public void setOutputFolder(String outputFolder) {
        this.outputFolder = outputFolder;
    }

    public void setNormMode(String normMode) {
        this.normMode = normMode;
    }
}