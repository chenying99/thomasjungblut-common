package de.jungblut.nlp;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset.Entry;

/**
 * MapReduce job that calculates the token frequency by an improved word count.
 * 
 * @author thomas.jungblut
 * 
 */
public class WordFrequencyJob {

  public static final String TOKENIZER_CLASS_KEY = "tokenizer.class";

  /**
   * Group the tokens in memory for each chunk, write it in the cleanup step.
   */
  public static class WordFrequencyMapper extends
      Mapper<LongWritable, Text, Text, LongWritable> {

    enum TokenCounter {
      NUM_TOKENS, COUNT_SUM
    }

    private final HashMultiset<String> wordCountSet = HashMultiset.create();
    private Tokenizer tokenizer;

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {

      final Configuration conf = context.getConfiguration();
      try {
        tokenizer = conf.getClass(TOKENIZER_CLASS_KEY, StandardTokenizer.class,
            Tokenizer.class).newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }

    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
        throws IOException, InterruptedException {

      String[] tokens = tokenizer.tokenize(value.toString());
      for (String token : tokens) {
        wordCountSet.add(token);
      }

    }

    @Override
    protected void cleanup(Context context) throws IOException,
        InterruptedException {
      // Guavas multiset counts the inserts, not the distinct keys.
      context.getCounter(TokenCounter.COUNT_SUM).increment(wordCountSet.size());
      Text key = new Text();
      LongWritable value = new LongWritable();
      for (Entry<String> entry : wordCountSet.entrySet()) {
        key.set(entry.getElement());
        value.set(entry.getCount());
        context.getCounter(TokenCounter.NUM_TOKENS).increment(1);
        context.progress();
        context.write(key, value);
      }
    }

  }

  /**
   * Group the tokens by reducing the mappers output and summing the sums for
   * each token.
   */
  public static class WordFrequencyReducer extends
      Reducer<Text, LongWritable, Text, LongWritable> {

    private final LongWritable sumValue = new LongWritable();

    @Override
    protected void reduce(Text key, Iterable<LongWritable> values,
        Context context) throws IOException, InterruptedException {

      long sum = 0l;
      for (LongWritable value : values) {
        sum += value.get();
      }
      sumValue.set(sum);
      context.write(key, sumValue);

    }

  }

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.out.println("Usage: <Comma separated input paths> <Output path>");
      System.exit(1);
    }
    Configuration conf = new Configuration();
    Job job = createJob(args[0], args[1], conf);

    job.waitForCompletion(true);

  }

  /**
   * Creates a token frequency job.
   * 
   * @param in the input path, may comma separate multiple paths.
   * @param out the output directory.
   * @param conf the configuration.
   * @return a job with the configured propertys like name, key/value classes
   *         and in/output format as text.
   */
  public static Job createJob(String in, String out, Configuration conf)
      throws IOException {
    Job job = new Job(conf, "Token Frequency Calculator");

    job.setInputFormatClass(TextInputFormat.class);
    job.setOutputFormatClass(TextOutputFormat.class);

    FileInputFormat.setInputPaths(job, in);
    FileOutputFormat.setOutputPath(job, new Path(out));

    job.setMapperClass(WordFrequencyMapper.class);
    job.setReducerClass(WordFrequencyReducer.class);
    job.setCombinerClass(WordFrequencyReducer.class);

    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(LongWritable.class);

    job.setNumReduceTasks(1);
    return job;
  }

}
