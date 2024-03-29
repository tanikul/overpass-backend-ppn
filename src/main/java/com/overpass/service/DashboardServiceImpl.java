package com.overpass.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.overpass.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.overpass.common.Constants.Status;
import com.overpass.common.Constants.StatusLight;
import com.overpass.common.Utils;
import com.overpass.reposiroty.DashboardRepository;
import com.overpass.reposiroty.MappingOverpassRepository;
import com.overpass.reposiroty.OverpassRepository;
import com.overpass.reposiroty.UserRepository;

import io.grpc.netty.shaded.io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DashboardServiceImpl implements DashboardService {

	@Autowired
	private DashboardRepository dashboardRepository;
	
	@Autowired
	private OverpassRepository overpassRepository;
	
	@Value("${getTokenUrl}")
	private String getTokenUrl;

	@Value("${refreshToken}")
	private String refreshTokenUrl;

	@Value("${overpassUrl}")
	private String overpassUrl;
	
	@Value("${lineNotifyUrl}")
	private String lineNotifyUrl;
	
	@Value("${firebase.server-key}")
	private String serverKey;
	
	@Value("${firebase.endpoint}")
	private String endpoint;
	
	@Autowired
	private RestTemplate restTemplate;
	
	@Autowired
	private FCMService fcmService;
	
	@Autowired
	private EmailService emailService;
	
	@Autowired
	private MappingOverpassRepository mappingOverpassRepository;
	
	@Autowired
	private UserRepository userRepository;

	private String token = "";
	
	@Override
	public Dashboard getDataDashBoard(Authentication authentication) {
		User u = userRepository.getUserByUsername(authentication.getName());
		Dashboard obj = new Dashboard();
		obj.setOverpassAll(dashboardRepository.countOverpassAll(u.getGroupId()));
		obj.setOverpassByZone(dashboardRepository.countOverpassByZone(u.getGroupId()));
		obj.setOverpassOffByMonth(dashboardRepository.getOverpassByMonth(StatusLight.OFF, u.getGroupId()));
		obj.setOverpassOnByMonth(dashboardRepository.getOverpassByMonth(StatusLight.ON, u.getGroupId()));
		obj.setOverpassOn(dashboardRepository.countOverpassAllByStatus(StatusLight.ON, u.getGroupId()));
		obj.setOverpassOff(dashboardRepository.countOverpassAllByStatus(StatusLight.OFF, u.getGroupId()));
		obj.setDonutChart(dashboardRepository.getDataDonutChart(u.getGroupId()));
		obj.setOverpassOnMax(dashboardRepository.getMaxOverpassByStatus(u.getGroupId(), StatusLight.ON));
		obj.setOverpassOffMax(dashboardRepository.getMaxOverpassByStatus(u.getGroupId(), StatusLight.OFF));
		int overall = (obj.getOverpassAll().containsKey("cnt") && obj.getOverpassAll().get("cnt") != null && obj.getOverpassAll().get("cnt") != "") ? Integer.parseInt(obj.getOverpassAll().get("cnt").toString()) : 0;
		if(overall == 0) {
			obj.setOverpassOffAverage(BigDecimal.ZERO);
		}else {
			obj.setOverpassOffAverage(new BigDecimal(obj.getOverpassOffMax()).divide(new BigDecimal(overall), 8, BigDecimal.ROUND_HALF_EVEN).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN));
		}
		return obj;
	}
	
	@Override
	public List<Integer> validateOverpass() {
		List<Integer> result = new ArrayList<>();
		try {
			SmartLight smartLight = new SmartLight();
			List<Overpass> overpasses = overpassRepository.getOverpassesByStatus(Status.ACTIVE);
			Map<String, String> overpassLastStatus = overpassRepository.getLastStatusOverpassStatus();
			try {
				callGetData(smartLight);
			} catch (HttpClientErrorException ex) {
				if (ex.getRawStatusCode() == HttpStatus.UNAUTHORIZED.value()) {
					ResponseEntity<Token>  tokenResponseEntity = restTemplate.exchange(getTokenUrl, HttpMethod.GET, null, Token.class);
					if (tokenResponseEntity.getStatusCode().equals(HttpStatus.OK)) {
						Token.Data data = tokenResponseEntity.getBody().getData();
						token = data.getIdToken();
						try {
							callGetData(smartLight);
						} catch(Exception e) {
							log.error("validateOverpass.UNAUTHORIZED: ", e);
						}
					}
				}
			} catch(Exception ex) {
				log.error("validateOverpass.overpassUrl: ", ex);
			}

			for(Overpass o : overpasses) {
				if(smartLight != null && smartLight.getStatus() == 200 && !smartLight.getResponse().isEmpty()) {

					Optional<SmartLightResponse> smartLightResponseOptional = smartLight.getResponse().stream().filter(response -> response.getIdOverpass().equals(o.getId())).findFirst();
					if (smartLightResponseOptional.isPresent()) {
						SmartLightResponse res = smartLightResponseOptional.get();
						OverpassStatus overpass = new OverpassStatus();
						overpass.setOverpassId(res.getIdOverpass());
						Date parsedDate;
						Timestamp timestamp;
						try {
							SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
							parsedDate = dateFormat.parse(res.getTimestamp());
							timestamp = new java.sql.Timestamp(parsedDate.getTime());
							overpass.setEffectiveDate(timestamp);
						} catch (ParseException e) {
							overpass.setEffectiveDate(new Timestamp(System.currentTimeMillis()));
						}
						overpass.setWatt(res.getWatt());
						Double w = 0.0;
						if(StatusLight.ON.name().equals(res.getStatus().toUpperCase()) && res.getWatt() < (o.getLightBulbCnt() * o.getLightBulb().getWatt())) {
							w = o.getLightBulbCnt() * o.getLightBulb().getWatt();
							overpass.setStatus(StatusLight.WARNING);
						}else if(StatusLight.ON.name().equals(res.getStatus().toUpperCase())) {
							overpass.setStatus(StatusLight.ON);
						}else if(StatusLight.OFF.name().equals(res.getStatus().toUpperCase())) {
							overpass.setStatus(StatusLight.OFF);
						}
						Map<String, MessageToNotify> messageToNotifys = new HashMap<>();
						if(overpassLastStatus.isEmpty() || !overpassLastStatus.containsKey(res.getIdOverpass()) || (overpassLastStatus.containsKey(res.getIdOverpass()) && !overpassLastStatus.get(res.getIdOverpass()).equals(overpass.getStatus().name()))) {
							overpass.setActive("Y");
							overpass.setDistrict(o.getDistrictName());
							overpass.setAmphur(o.getAmphurName());
							overpass.setProvince(o.getProvinceName());
							overpass.setLocation(o.getLocation());
							overpass.setLatitude(o.getLatitude());
							overpass.setLongtitude(o.getLongtitude());
							String mapUrl = "http://www.google.com/maps/place/" + o.getLatitude() + "," + o.getLongtitude();
							overpass.setMapUrl(mapUrl);
							overpass.setId("" + res.getId());

							MessageToNotify messageNotify = getMessageToNotify(overpass, o.getProvince(), w);
							messageNotify.setId(overpass.getId());
							overpassRepository.updateActiveOverpassStatus(overpass.getOverpassId());
							overpass.setTopic(messageNotify.getTopic());
							overpass.setLocationDisplay(messageNotify.getLocation());
							Integer seq = dashboardRepository.getSeqOverpassStatusByOverpassIdAndStatus(overpass.getId(), overpass.getStatus());
							seq = (seq == null) ? 1 : seq;
							overpass.setSeq(seq);
							overpassRepository.insertOverpassStatus(overpass);
							senNotificationToLine(o.getId(), messageNotify);
							sendNotifyToWeb(o.getId(), messageNotify);
							messageToNotifys.put(overpass.getOverpassId(), messageNotify);
							//o.setOverpassStatus(overpass.getStatus().name());
							//o.setSetpointWatt(res.getWatt());
							List<GroupOverpass> groups = mappingOverpassRepository.getGroupByOverpassId(o.getId());
							for(GroupOverpass group : groups) {
								result.add(group.getId());
							}
						}
						//sendEmail(messageToNotifys);
					}

				}
			}
		}catch(Exception ex) {
			log.error(ex.getMessage());
		}
		return result;
	}

	private void callGetData(SmartLight smartLight) {
		ParameterizedTypeReference<Map<String, ObjectNode>> responseType = new ParameterizedTypeReference<Map<String, ObjectNode>>() {};
		String getDataUrl = overpassUrl.replace(":token", token);
		ResponseEntity<Map<String, ObjectNode>> rest = restTemplate.exchange(getDataUrl, HttpMethod.GET, null, responseType);
		Map<String, ObjectNode> map = rest.getBody();
		smartLight.setStatus(200);
		List<SmartLightResponse> smartLightResponses = new ArrayList<>();
		for (Map.Entry<String, ObjectNode> node : map.entrySet()) {
			ObjectNode objectNode = node.getValue();
			JsonNode jsonNodes = objectNode.get("sensors");
			jsonNodes.forEach(jn -> {
				if (jn.has("subsensors")) {
					jn.get("subsensors").forEach(s -> {
						if (s.get("name").asText().equals("ActivePowerTotal")) {
							Double power = (s.has("value")) ? (double) Math.round((s.get("value").asDouble() * 1000) * 100) / 100 : 0;
							SmartLightResponse smartLightResponse = new SmartLightResponse();
							smartLightResponse.setIdOverpass(node.getKey());
							smartLightResponse.setWatt(power);
							smartLightResponse.setStatus((s.has("value")) ? StatusLight.ON.name() : StatusLight.OFF.name());
							smartLightResponse.setTimestamp(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneId.systemDefault())));
							smartLightResponses.add(smartLightResponse);
							smartLight.setResponse(smartLightResponses);
						}
					});
				}
			});
		}
	}

	private void senNotificationToLine(String overpassId, MessageToNotify messageNotify){
		try {
			List<GroupOverpass> list = mappingOverpassRepository.getGroupByOverpassId(overpassId);
			for(GroupOverpass item : list) {
				HttpHeaders headers = new HttpHeaders();
				headers.setAccept(Collections.singletonList(MediaType.APPLICATION_FORM_URLENCODED));
				headers.set("Authorization", "Bearer " + item.getLineNotiToken());
				MultiValueMap<String, String> map= new LinkedMultiValueMap<>();
				String str = "";
				str += messageNotify.getTopic() + "\n";
				if(!StringUtil.isNullOrEmpty(messageNotify.getLocation())) {
					str += "สถานที่: " + messageNotify.getLocation() + "\n";
				}
				if(!StringUtil.isNullOrEmpty(messageNotify.getNote())) {
					str += "Note: " + messageNotify.getNote() + "\n";
				}
				if(!StringUtil.isNullOrEmpty(messageNotify.getTimeToHang())) {
					str += "วันเวลาที่ได้รับแจ้ง: " + messageNotify.getTimeToHang() + "\n";
				}
				if(!StringUtil.isNullOrEmpty(messageNotify.getCoordinate())) {
					str += "พิกัด: " + messageNotify.getCoordinate();
				}
				map.add("message", str);
				HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<MultiValueMap<String, String>>(map, headers);
				restTemplate.exchange(lineNotifyUrl, HttpMethod.POST, request, String.class);
			}
		}catch(Exception ex) {
			throw ex;
		}
	}
	
	private void sendNotifyToWeb(String overpassId, MessageToNotify messageNotify) {
		try {
			List<GroupOverpass> list = mappingOverpassRepository.getGroupByOverpassId(overpassId);
			for(GroupOverpass item : list) {
				HttpHeaders headers = new HttpHeaders();
				headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
				headers.add("Authorization", serverKey);
				
				PostNotification post = new PostNotification();
				post.setTo("/topics/overpass-" + item.getId());
				post.setData(messageNotify);
				HttpEntity<PostNotification> entity = new HttpEntity<>(post, headers);
				restTemplate.postForObject(endpoint, entity, String.class);
			}
		}catch(Exception ex) {
			throw ex;
		}
	}
	
	private MessageToNotify getMessageToNotify(OverpassStatus overpass, int provinceId, Double watt) {
		MessageToNotify noti = new MessageToNotify();
		try {
			noti.setStatus(overpass.getStatus());
			if(StatusLight.OFF.equals(overpass.getStatus())){
				noti.setTopic("แจ้งเตือนหลอดไฟดับ ");
			}else if(StatusLight.WARNING.equals(overpass.getStatus())){
				noti.setTopic("แจ้งเตือนพลังงานไฟลดลง " + overpass.getWatt() + " < " + watt + " watt");
			}else if(StatusLight.ON.equals(overpass.getStatus())){
				noti.setTopic("แจ้งเตือนหลอดไฟเปิด ");
			}
			String location = "";
			if(provinceId == 1) {
				if(!StringUtil.isNullOrEmpty(overpass.getAmphur())) {
					location += overpass.getAmphur() + " ";
				}
				if(!StringUtil.isNullOrEmpty(overpass.getDistrict())) {
					location += "แขวง" + overpass.getDistrict() + " ";
				}
				if(!StringUtil.isNullOrEmpty(overpass.getProvince())) {
					location += overpass.getProvince();
				}
				
			}else {
				if(!StringUtil.isNullOrEmpty(overpass.getDistrict())) {
					location += "ต." + overpass.getDistrict() + " ";
				}
				if(!StringUtil.isNullOrEmpty(overpass.getAmphur())) {
					location += "อ." + overpass.getAmphur() + " ";
				}
				if(!StringUtil.isNullOrEmpty(overpass.getProvince())) {
					location += "จ." + overpass.getProvince();
				}
			}
			noti.setCoordinate(overpass.getMapUrl());
			noti.setLocation(location);
			noti.setNote(overpass.getLocation());
			noti.setTimeToHang(Utils.dateFormatToString(Utils.convertTimestampToDate(overpass.getEffectiveDate()), "dd/MM/yyyy HH:mm:ss"));
			
			return noti;
		}catch(Exception ex) {
			throw ex;
		}
	}

	@Override
	public Dashboard getDataDashBoard(Integer groupId) {
		Dashboard obj = new Dashboard();
		obj.setOverpassAll(dashboardRepository.countOverpassAll(groupId));
		obj.setOverpassByZone(dashboardRepository.countOverpassByZone(groupId));
		obj.setOverpassOffByMonth(dashboardRepository.getOverpassByMonth(StatusLight.OFF, groupId));
		obj.setOverpassOnByMonth(dashboardRepository.getOverpassByMonth(StatusLight.ON, groupId));
		obj.setOverpassOn(dashboardRepository.countOverpassAllByStatus(StatusLight.ON, groupId));
		obj.setOverpassOff(dashboardRepository.countOverpassAllByStatus(StatusLight.OFF, groupId));
		obj.setDonutChart(dashboardRepository.getDataDonutChart(groupId));
		obj.setOverpassOnMax(dashboardRepository.getMaxOverpassByStatus(groupId, StatusLight.ON));
		obj.setOverpassOffMax(dashboardRepository.getMaxOverpassByStatus(groupId, StatusLight.OFF));
		int overall = (obj.getOverpassAll().containsKey("cnt") && obj.getOverpassAll().get("cnt") != null && obj.getOverpassAll().get("cnt") != "") ? Integer.parseInt(obj.getOverpassAll().get("cnt").toString()) : 0;
		if(overall == 0) {
			obj.setOverpassOffAverage(BigDecimal.ZERO);
		}else {
			obj.setOverpassOffAverage(new BigDecimal(obj.getOverpassOffMax()).divide(new BigDecimal(overall)).multiply(new BigDecimal(100)).setScale(2, BigDecimal.ROUND_HALF_EVEN));
		}
		return obj;
	}
	
	private void sendEmail(Map<String, MessageToNotify> obj) {
		try {
			for(Map.Entry<String, MessageToNotify> item : obj.entrySet()) {
				List<GroupOverpass> list = mappingOverpassRepository.getGroupByOverpassId(item.getKey());
				for(GroupOverpass group : list) {
					String subject = item.getValue().getTopic();
					String body = item.getValue().getTopic();
					body += "\n\n              ";
					body += "สถานที่ : " + item.getValue().getLocation();
					if(!StringUtil.isNullOrEmpty(item.getValue().getNote())) {
						body += "\n              ";
						body += "Note : " + item.getValue().getNote();
					}
					body += "\n              ";
					body += "วันเวลาที่ได้รับแจ้ง : " + item.getValue().getTimeToHang();
					body += "\n              ";
					body += "พิกัด : " + item.getValue().getCoordinate();
					body += "\n\n";
		 			emailService.sendSimpleMessage(group.getEmail(), subject, body);
				}
			}
			
		}catch(Exception ex) {
			log.error(ex.getMessage());
			throw ex;
		}
	}
}
