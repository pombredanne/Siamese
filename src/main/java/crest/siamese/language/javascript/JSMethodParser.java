package crest.siamese.language.javascript;

import crest.siamese.document.Method;
import crest.siamese.language.MethodParser;
import crest.siamese.settings.Settings;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * This class responsible for extracting all the JavaScript function block as Method objects
 */
public class JSMethodParser implements MethodParser {

    private String FILE_PATH;
    private String MODE;

    /**
     * Constructor that refer to parent Object class. Required for calling Class::newInstance.
     */
    public JSMethodParser() {
        super();
    }

    /**
     * Initialises a JSMethodParser
     *
     * @param filePath       Location of input file
     * @param prefixToRemove Legacy parameter
     * @param mode           Method level or Class level parsing
     * @param isPrint        Legacy parameter
     */
    public JSMethodParser(String filePath, String prefixToRemove, String mode, boolean isPrint) {
        this.FILE_PATH = filePath;
        this.MODE = mode;
    }

    /**
     * License extraction is not implemented for JavaScript
     *
     * @return null
     */
    @Override
    public String getLicense() {
        return null;
    }

    /**
     * Configures constructor attributes when initialised with Class.newInstance() in Siamese.java
     *
     * @param filePath       Location of input file
     * @param prefixToRemove Legacy parameter
     * @param mode           Method level or Class level parsing
     * @param isPrint        Legacy parameter
     */
    @Override
    public void configure(String filePath, String prefixToRemove, String mode, boolean isPrint) {
        this.FILE_PATH = filePath;
        this.MODE = mode;
    }

    /**
     * Uses ANTLR4 generated Parser and Lexer to extract methods from JavaScript source code file
     *
     * @return ArrayList of methods extracted from FILE_PATH attribute
     */
    @Override
    public ArrayList<Method> parseMethods() {
        ArrayList<Method> methods = new ArrayList<>();
        File file = new File(this.FILE_PATH);
        if (MODE.equals(Settings.MethodParserType.METHOD)) {
            JSParseTreeListener jsParseTreeListener = getTraversedJSParseTreeListener(file);
            methods.addAll(jsParseTreeListener.getJSMethods());
        } else {
            ParseTree parseTree = getParsedTree(file);
            JSParseTreeListener jsParseTreeListener = new JSParseTreeListener(file.getAbsolutePath());
            methods.add(jsParseTreeListener.getFileBlockMethod(parseTree));
        }
        return methods;
    }


    /**
     * Create a Traversed JSParseTreeListener for a given JavaScript source file.
     *
     * @param sourceFile JavaScript source file
     * @return A fully Traversed JSParseTreeListener instance
     */
    protected JSParseTreeListener getTraversedJSParseTreeListener(File sourceFile) {
        ParseTree parseTree = getParsedTree(sourceFile);
        JSParseTreeListener jsParseTreeListener = new JSParseTreeListener(sourceFile.getPath());
        ParseTreeWalker.DEFAULT.walk(jsParseTreeListener, parseTree);
        return jsParseTreeListener;
    }

    /**
     * Create a  ParseTree for a given JavaScript source file.
     *
     * @param sourceFile JavaScript source file
     * @return An instance of a ParseTree fot the give JavaScript source file.
     */

    protected ParseTree getParsedTree(File sourceFile) {
        try {
            JavaScriptParser parser = getJavaScriptParser(sourceFile);
            ParseTree parseTree = parser.program();
            return parseTree;
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.println("Parsing Errors Occurs at source file: " + sourceFile.getPath());
        }
        JavaScriptParser parser = getJavaScriptParser(new File("Empty.js"));
        ParseTree parseTree = parser.program();
        return parseTree;
    }


    /**
     * Create a  JavaScriptParser for a given JavaScript source file.
     *
     * @param sourceFile JavaScript source file
     * @return An instance of a JavaScriptParser fot the give JavaScript source file.
     */

    protected JavaScriptParser getJavaScriptParser(File sourceFile) {
        CharStream sourceStream = getSourceAsCharStreams(sourceFile);
        JavaScriptParser parser = new Builder.Parser(sourceStream).build();
        return parser;
    }


    /**
     * Read file content as CharStream for a given file
     *
     * @param sourceFile Source file
     * @return File content as CharStream and if IOException occurs then return CharStream of an empty string.
     */
    protected CharStream getSourceAsCharStreams(File sourceFile) {
        CharStream input = CharStreams.fromString(" ");
        try {
            Path sourcePath = Paths.get(sourceFile.getPath());
            input = CharStreams.fromPath(sourcePath);
            return input;
        } catch (IOException e) {
            System.err.println(e.toString());
            return input;
        }
    }

}
