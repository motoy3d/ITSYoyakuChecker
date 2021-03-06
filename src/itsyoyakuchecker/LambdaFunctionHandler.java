package itsyoyakuchecker;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailServiceClient;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.MessageAttributeValue;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;

/**
 * ITSの保養施設の空き状況をWEBサイトから取得して金曜～日曜の空き状況を通知する。
 * AWS Lambdaにより1分おきに実行される。
 * DynamoDBに前回チェック時の空き状況を保存し、前回と違う状況になった時にのみ通知を行う。
 * 通知方法はSMS送信(Amazon SNS)、メール送信(Amazon SES)。
 * TODO 祝日対応 http://calendar-service.net/api.php
 *
 * @author Motoi Kataoka
 *
 */
public class LambdaFunctionHandler implements RequestHandler<Object, Object> {
	private static ResourceBundle prop = ResourceBundle.getBundle("app");
	private static final String YOYAKU_URL_TOP = "https://as.its-kenpo.or.jp";
	private static final String[] TARGET_HOYO_SHISETSU = new String[] {
			"トスラブ箱根ビオーレ", "トスラブ箱根和奏林", "トスラブ館山ルアーナ" };
	private static final int[] TARGET_DAYS = new int[] {
			Calendar.SATURDAY,
			Calendar.SUNDAY,
			Calendar.FRIDAY
			};
	private static final String ITS_TEL = "003768-03-5925-5348";
	private static final String HOKENSHO_INFO = prop.getString("hokensho.info");
	// SMS送信用設定
	private static final Region SNS_REGION = Region.getRegion(Regions.AP_NORTHEAST_1);	//東京
	private static final String SMS_MAX_PRICE = "5.00";	//SMS送信料金の最大金額($USD)。これに達するとSMSが送信されなくなる。
	private static final String[] SMS_TARGET_PHONE_NUMBERS = new String[] {
			prop.getString("sms.target.0")
			,prop.getString("sms.target.1")
			};

	// メール送信用設定
	private static final Regions SES_REGION = Regions.US_WEST_2;	//オレゴン
	private static final String FROM = prop.getString("mail.from");
	private static final String[] TO = new String[] {prop.getString("mail.to.0"), prop.getString("mail.to.1")};
	private static final String SUBJECT = "ITS健保施設予約チェック";

	private static Region DYNAMODB_REGION = Region.getRegion(Regions.AP_NORTHEAST_1);
	private static String TABLE_NAME = "ITSCheckResult";

	@Override
	public Object handleRequest(Object input, Context context) {
//		context.getLogger().log("Input: " + input);
		try {
			Document doc = Jsoup.connect(YOYAKU_URL_TOP).maxBodySize(0).timeout(60 * 1000).get();
			Elements elements = doc.select("dl.service_category > dt > a");
			String akishokaiUrl = null;
			for (Element ele : elements) {
				if (ele.text().equals("直営・通年・夏季保養施設(空き照会)")) {
					String href = ele.attr("href");
					// System.out.println(ele.text() + " " + href);
					akishokaiUrl = YOYAKU_URL_TOP + href;
					break;
				}
			}
			// System.out.println("----------------------------");

			doc = Jsoup.connect(akishokaiUrl).maxBodySize(0).timeout(60 * 1000).get();
			elements = doc.select("li > a");
			List<String> shisetsuUrlList = new ArrayList<String>();
			for (Element ele : elements) {
				if (ArrayUtils.contains(TARGET_HOYO_SHISETSU, ele.text())) {
					String href = ele.attr("href");
					// System.out.println(ele.text() + " " + href);
					shisetsuUrlList.add(YOYAKU_URL_TOP + href);
				}
			}
			// System.out.println("----------------------------");

			String content = "";
			for (String shisetsuUrl : shisetsuUrlList) {
				// System.out.println("##########################");
				doc = Jsoup.connect(shisetsuUrl).maxBodySize(0).timeout(60 * 1000).get();
				elements = doc.select("li > a");
				for (Element ele : elements) {
					String href = ele.attr("href");
					String shisetsuMei = ele.text();
					// System.out.println(shisetsuMei + " " + href);
					String shisetsuMonthUrl = YOYAKU_URL_TOP + href;
					shisetsuMei = shisetsuMei.substring(0, shisetsuMei.indexOf("　"));
					content += checkDate(shisetsuMei, shisetsuMonthUrl);
				}
			}
			// System.out.println("----------------------------");

			// DynamoDBから前回のチェック結果を取得して更新があれば通知する。
			boolean isUpdated = isUpdated(content);

			if (isUpdated) {
				if ("".equals(content)) {
					content = "空きはなくなりました。";
				} else {
					content += "\n" + ITS_TEL + "\n" + HOKENSHO_INFO;
				}
				sendMail(content);
				//TODO SMS_MAX_PRICEで定義した金額を超えて課金された上にSMSが届かない現象があるため、送信しないようにする
//				sendSMS(content);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * 空き照会ページを読み込んで、空きがあれば内容を返す。
	 *
	 * @param shisetsuMei
	 * @param shisetsuMonthUrl
	 * @throws IOException
	 * @throws ParseException
	 */
	private static String checkDate(String shisetsuMei, String shisetsuMonthUrl) throws IOException, ParseException {
		Document doc = Jsoup.connect(shisetsuMonthUrl).maxBodySize(0).timeout(60 * 1000).get();

		Elements elements = doc.select("select");
		if (elements.isEmpty()) {
			System.out.println("空きなし");
			return "";
		}
		Element datePulldown = elements.get(0);
		Elements dateOptions = datePulldown.select("option");
		SimpleDateFormat df = new SimpleDateFormat("yyyy年MM月dd日");
		SimpleDateFormat df2 = new SimpleDateFormat("M月d日(E)");
		String content = "";
		for (Element opt : dateOptions) {
			String date = opt.text();
			if (!"".equals(date)) {
				Calendar cal = Calendar.getInstance(Locale.JAPAN);
				cal.setTime(df.parse(date));
				int day = cal.get(Calendar.DAY_OF_WEEK);
				if (ArrayUtils.contains(TARGET_DAYS, day)) {
					content += shisetsuMei.replace("トスラブ", "") + " "
							+ df2.format(cal.getTime()) + " " + shisetsuMonthUrl + "\n";
					System.out.println(content);
				}
			}
		}
		if ("".equals(content)) {
			return "";
		}
		return content;
	}

	/**
	 * DynamoDBから前回チェック結果を取得して更新があるかどうかを返す。
	 */
	private static boolean isUpdated(String content) {
		System.out.println("DynamoDBから前回チェック結果を取得");
		AmazonDynamoDBClient client = new AmazonDynamoDBClient(/*new ProfileCredentialsProvider()*/);
		client.setRegion(DYNAMODB_REGION);
		DynamoDB dynamoDB = new DynamoDB(client);
		Table table = dynamoDB.getTable(TABLE_NAME);
		Item item = table.getItem("id", 1);
		String lastResult = (String)item.get("lastResult");
		if (StringUtils.equals(content, lastResult)) {
			// 前回と同じ
			return false;
		}
		// 前回と違う場合は今回の結果をDynamoDBに保存
		UpdateItemSpec updateItemSpec = new UpdateItemSpec()
				.withPrimaryKey("id", 1)
				.withUpdateExpression("set lastResult = :r, updatedAt = :u")
				.withValueMap(new ValueMap()
						.withString(":r", content)
						.withString(":u", new Date().toString())
						)
				.withReturnValues(ReturnValue.UPDATED_NEW);
		UpdateItemOutcome outcome = table.updateItem(updateItemSpec);
		System.out.println("DB更新内容：" + outcome.getItem().toJSONPretty());
		return true;
	}


	/**
	 * SNSでSMS送信
	 *
	 * @param message
	 */
	private static void sendSMS(String message) {
		AmazonSNSClient snsClient = new AmazonSNSClient();
		snsClient.setRegion(SNS_REGION);
		Map<String, MessageAttributeValue> smsAttributes = new HashMap<String, MessageAttributeValue>();
		smsAttributes.put("AWS.SNS.SMS.SenderID", new MessageAttributeValue()
		        .withStringValue("ITSChecker") //The sender ID shown on the device.
		        .withDataType("String"));
		smsAttributes.put("AWS.SNS.SMS.MaxPrice", new MessageAttributeValue()
		        .withStringValue(SMS_MAX_PRICE) //Sets the max price to 0.50 USD.
		        .withDataType("Number"));
		smsAttributes.put("AWS.SNS.SMS.SMSType", new MessageAttributeValue()
		        .withStringValue("Promotional") //Sets the type to promotional.
		        .withDataType("String"));
		for (String phoneNumber : SMS_TARGET_PHONE_NUMBERS) {
			PublishResult result = snsClient.publish(new PublishRequest().withMessage(message).withPhoneNumber(phoneNumber)
					.withMessageAttributes(smsAttributes));
			System.out.println("SMS送信結果(" + phoneNumber + ") " + result); // Prints the message ID.
		}
	}

	/**
	 * SESでメール送信
	 *
	 * @param content
	 */
	private static void sendMail(String content) {
		// Construct an object to contain the recipient address.
		Destination destination = new Destination().withToAddresses(TO);

		// Create the subject and body of the message.
		Content subject = new Content().withData(SUBJECT);
		Content textBody = new Content().withData(content);
		Body body = new Body().withText(textBody);

		// Create a message with the specified subject and body.
		Message message = new Message().withSubject(subject).withBody(body);

		// Assemble the email.
		SendEmailRequest request = new SendEmailRequest().withSource(FROM).withDestination(destination)
				.withMessage(message);

		try {
			System.out.println("Attempting to send an email through Amazon SES by using the AWS SDK for Java...");

			AmazonSimpleEmailServiceClient client = new AmazonSimpleEmailServiceClient();

			Region REGION = Region.getRegion(SES_REGION);
			client.setRegion(REGION);

			// Send the email.
			client.sendEmail(request);
			System.out.println("Email sent!");
		} catch (Exception ex) {
			System.out.println("The email was not sent.");
			ex.printStackTrace();
		}
	}

//	public static void main(String[] args) {
//		LambdaFunctionHandler handler = new LambdaFunctionHandler();
//		handler.handleRequest(null, null);
//	}
}
