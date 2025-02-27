package io.ipolyzos.producers.json;

import io.ipolyzos.config.AppConfig;
import io.ipolyzos.models.Transaction;
import io.ipolyzos.utils.ClientUtils;
import io.ipolyzos.utils.DataSourceUtils;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.impl.schema.AvroSchema;
import org.apache.pulsar.client.impl.schema.JSONSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
public class TransactionsProducer {
    private static final Logger logger
            = LoggerFactory.getLogger(TransactionsProducer.class);

    public static void main(String[] args) throws IOException {
        Stream<Transaction> transactions = DataSourceUtils
                .loadDataFile(AppConfig.TRANSACTIONS_FILE_PATH)
                .map(DataSourceUtils::toTransaction);

        logger.info("Creating Pulsar Client ...");

        PulsarClient pulsarClient = ClientUtils.initPulsarClient(AppConfig.token);

        logger.info("Creating Transactions Producer ...");
        Producer<Transaction> transactionProducer
                = pulsarClient.newProducer(JSONSchema.of(Transaction.class))
                .producerName("txn-avro-producer")
                .topic(AppConfig.TRANSACTIONS_TOPIC_JSON)
                .blockIfQueueFull(true)
                .create();

        AtomicInteger counter = new AtomicInteger(1);
        for (Iterator<Transaction> it = transactions.iterator(); it.hasNext(); ) {
            Transaction transaction = it.next();

            transactionProducer
                    .newMessage()
                    .key(transaction.getCustomerId())
                    .value(transaction)
                    .eventTime(System.currentTimeMillis())
                    .sendAsync()
                    .whenComplete(callback(counter));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Sent '{}' customer records.", counter.get());
            logger.info("Closing Resources...");
            try {
                transactionProducer.close();
                pulsarClient.close();
            } catch (PulsarClientException e) {
                e.printStackTrace();
            }
        }));
    }

    private static BiConsumer<MessageId, Throwable> callback(AtomicInteger counter) {
        return (id, exception) -> {
            if (exception != null) {
                logger.error("❌ Failed message: {}", exception.getMessage());
            } else {
                logger.info("✅ Acked message {} - Total {}", id, counter.getAndIncrement());
            }
        };
    }
}
