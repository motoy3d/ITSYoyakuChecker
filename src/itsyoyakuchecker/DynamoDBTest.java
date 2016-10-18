package itsyoyakuchecker;

import java.io.IOException;
import java.util.Date;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;

public class DynamoDBTest {

	static Region DYNAMODB_REGION = Region.getRegion(Regions.AP_NORTHEAST_1);
	static String tableName = "ITSCheckResult";

	public static void main(String[] args) throws IOException {
		AmazonDynamoDBClient client = new AmazonDynamoDBClient(new ProfileCredentialsProvider());
		client.setRegion(DYNAMODB_REGION);
		DynamoDB dynamoDB = new DynamoDB(client);

		Table table = dynamoDB.getTable(tableName);
		try {
			Item item = table.getItem("id", 1);
			System.out.println("item=" + item.toJSONPretty());

			UpdateItemSpec updateItemSpec = new UpdateItemSpec()
		            .withPrimaryKey("id", 1)
		            .withUpdateExpression("set lastResult = :r, updatedAt = :u")
		            .withValueMap(new ValueMap()
			            .withString(":r", "test")
			            .withString(":u", new Date().toString())
		            )
		            .withReturnValues(ReturnValue.UPDATED_NEW);
			UpdateItemOutcome outcome = table.updateItem(updateItemSpec);
			System.out.println(outcome.getItem().toJSONPretty());
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
