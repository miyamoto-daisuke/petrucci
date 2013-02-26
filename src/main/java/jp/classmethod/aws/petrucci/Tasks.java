/*
 * Copyright 2013 Classmethod, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package jp.classmethod.aws.petrucci;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.simpleemail.AmazonSimpleEmailService;
import com.amazonaws.services.simpleemail.model.Body;
import com.amazonaws.services.simpleemail.model.Content;
import com.amazonaws.services.simpleemail.model.Destination;
import com.amazonaws.services.simpleemail.model.GetIdentityVerificationAttributesRequest;
import com.amazonaws.services.simpleemail.model.GetIdentityVerificationAttributesResult;
import com.amazonaws.services.simpleemail.model.IdentityVerificationAttributes;
import com.amazonaws.services.simpleemail.model.Message;
import com.amazonaws.services.simpleemail.model.SendEmailRequest;

@Component
@SuppressWarnings("javadoc")
public class Tasks implements InitializingBean {
	
	private static Logger logger = LoggerFactory.getLogger(Tasks.class);
	
	@Autowired
	AmazonEC2 ec2;
	
	@Autowired
	AmazonS3 s3;
	
	@Autowired
	AmazonSimpleEmailService ses;
	
	@Value("${petrucci.mailaddress}")
	String mailaddress;
	
	
	@Scheduled(cron = "* * * * * *")
	public void timeKeeper() {
		DateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		logger.info(df.format(new Date()));
	}
	
	@Scheduled(cron = "0 */2 * * * *")
	public void reportS3AndEC2() {
		logger.info("report start");
		DateFormat df = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
		StringBuilder sb = new StringBuilder();
		
		logger.info("check S3 buckets");
		sb.append("== S3 Buckets ==\n");
		try {
			for (Bucket bucket : s3.listBuckets()) {
				sb.append(String.format("%s (created:%s)%n",
						bucket.getName(),
						df.format(bucket.getCreationDate())));
			}
		} catch (AmazonClientException e) {
			logger.error("unexpected exception", e);
		}
		
		sb.append("\n");
		
		logger.info("check EC2 instances");
		sb.append("== EC2 Instances ==").append("\n");
		try {
			DescribeInstancesResult result = ec2.describeInstances();
			for (Reservation reservation : result.getReservations()) {
				for (Instance instance : reservation.getInstances()) {
					sb.append(String.format("%s %s %s%n",
							instance.getInstanceId(),
							instance.getImageId(),
							instance.getInstanceType(),
							instance.getInstanceLifecycle()));
				}
			}
		} catch (AmazonClientException e) {
			logger.error("unexpected exception", e);
		}
		
		if (logger.isInfoEnabled()) {
			Scanner scanner = null;
			try {
				scanner = new Scanner(sb.toString());
				while (scanner.hasNextLine()) {
					logger.info("{}", scanner.nextLine());
				}
			} finally {
				if (scanner != null) {
					scanner.close();
				}
			}
		}
		
		logger.info("send report mail");
		ses.sendEmail(new SendEmailRequest(
				mailaddress,
				new Destination(Collections.singletonList(mailaddress)),
				new Message(new Content("S3 & EC2 Report"), new Body(new Content(sb.toString())))));
		
		logger.info("report end");
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		try {
			GetIdentityVerificationAttributesResult result = ses.getIdentityVerificationAttributes(
				new GetIdentityVerificationAttributesRequest()
					.withIdentities(mailaddress));
			Map<String, IdentityVerificationAttributes> attrs = result.getVerificationAttributes();
			if (attrs.containsKey(mailaddress) == false
					|| attrs.get(mailaddress).getVerificationStatus().equals("Success") == false) {
				throw new Exception(
						String.format("verification status of [%s] is not \"Success\"", mailaddress));
			}
		} catch (AmazonServiceException e) {
			logger.error("unexpected :" + mailaddress, e);
			throw e;
		}
	}
}
