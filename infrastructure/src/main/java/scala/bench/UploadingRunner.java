package scala.bench;

import org.openjdk.jmh.runner.Defaults;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.format.OutputFormat;
import org.openjdk.jmh.runner.format.OutputFormatFactory;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.util.UnCloseablePrintStream;
import org.openjdk.jmh.util.Utils;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

public class UploadingRunner {
    public static void main(String[] args) throws Exception {
        CommandLineOptions commandLineOptions = new CommandLineOptions(args);
        Runner runner = new Runner(commandLineOptions, new UploadingOutputFormat(createOutputFormat(commandLineOptions)));
        runner.run();
    }

    private static OutputFormat createOutputFormat(Options options) {
        // sadly required here as the check cannot be made before calling this method in constructor
        if (options == null) {
            throw new IllegalArgumentException("Options not allowed to be null.");
        }

        PrintStream out;
        if (options.getOutput().hasValue()) {
            try {
                out = new PrintStream(options.getOutput().get());
            } catch (FileNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
        } else {
            // Protect the System.out from accidental closing
            try {
                out = new UnCloseablePrintStream(System.out, Utils.guessConsoleEncoding());
            } catch (UnsupportedEncodingException ex) {
                throw new IllegalStateException(ex);
            }
        }

        return OutputFormatFactory.createFormatInstance(out, options.verbosity().orElse(Defaults.VERBOSITY));
    }
}
