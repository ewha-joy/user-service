package com.example.userservice.messageqeue;

import com.example.userservice.Model.User;
import com.example.userservice.Repository.UserRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;


@Service
public class KafkaConsumer {

    @Autowired
    UserRepository userRepository;

    @Autowired
    public KafkaConsumer(UserRepository userRepository){
        this.userRepository = userRepository;
    }

    @KafkaListener(topics = "mongo_source.testdb.cashRecord")
    public void updateUserCash(String kafkaMessage){
        Map<Object, Object> map = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        JSONObject jObject = new JSONObject();
        try{
            map = mapper.readValue(kafkaMessage, new TypeReference<Map<Object, Object>>() {});
            jObject = new JSONObject(map.get("payload").toString());
            jObject = new JSONObject(jObject.get("fullDocument").toString());
            // kafka message에서 cash 내용 읽어오기
            Optional<User> user = userRepository.findById(Long.valueOf(jObject.get("userid").toString()));
            String change_amount =  new JSONObject(jObject.get("change_amount").toString()).get("$numberLong").toString();
            User currentuser = user.orElse(null);
            if(currentuser != null){
                String x = (String) jObject.get("content");
                switch (x){
                    case "CHARGE":
                        currentuser.setCash(currentuser.getCash() + Long.valueOf(change_amount));
                        break;
                    case "RENT_WEBTOON":
                        currentuser.setCash(currentuser.getCash() - Long.valueOf(change_amount));
                        break;
                    case "MONTH_REVENUE":
                        currentuser.setCash(currentuser.getCash() + Long.valueOf(change_amount));
                        break;
                }
                userRepository.save(currentuser);
            }
            }
        catch (Exception ex){
            ex.printStackTrace();
        }
    }
}
