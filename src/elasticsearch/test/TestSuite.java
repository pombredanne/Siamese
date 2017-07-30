package elasticsearch.test;

/**
 * Created by Chaiyong on 4/5/17.
 */
import elasticsearch.document.Document;
import elasticsearch.main.Main;
import org.junit.runners.Suite;
import org.junit.runner.RunWith;

@RunWith(Suite.class)
@Suite.SuiteClasses({DocumentTest.class, JavaTokenizerTest.class,
MethodParserTest.class, nGramGeneratorTest.class})
public class TestSuite {
    //nothing
}