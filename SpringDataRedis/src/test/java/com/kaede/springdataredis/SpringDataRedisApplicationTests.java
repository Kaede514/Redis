package com.kaede.springdataredis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaede.springdataredis.pojo.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@SpringBootTest
class SpringDataRedisApplicationTests {

    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // JSON工具
    private ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testString() {
        ValueOperations opsForValue = redisTemplate.opsForValue();
        // 字符串和Object均可传入，底层有自动序列化机制
        opsForValue.set("name", "张三");
        Object name = opsForValue.get("name");
        System.out.println("name = " + name);
    }

    @Test
    public void testSaveUser() {
        List<String> hobbies = Arrays.asList("anime", "game", "sleep");
        User user = new User("kaede", 22, hobbies);
        ValueOperations opsForValue = redisTemplate.opsForValue();
        opsForValue.set("user1", user);
        User user1 = (User) opsForValue.get("user1");
        System.out.println("user1 = " + user1);
    }

    @Test
    public void testSaveUser2() throws JsonProcessingException {
        List<String> hobbies = Arrays.asList("anime", "game", "sleep");
        User user = new User("kaede", 22, hobbies);
        // 手动序列化
        String stringUser = mapper.writeValueAsString(user);
        ValueOperations<String, String> opsForValue = stringRedisTemplate.opsForValue();
        opsForValue.set("user2", stringUser);
        // 手动反序列化
        String jsonUser = opsForValue.get("user2");
        User user2 = mapper.readValue(jsonUser, User.class);
        System.out.println("user2 = " + user2);
    }

    @Test
    public void testHash() {
        HashOperations<String, String, String> opsForHash = stringRedisTemplate.opsForHash();
        List<String> hobbies = Arrays.asList("anime", "game", "sleep");
        opsForHash.put("user3", "name", "kaede");
        opsForHash.put("user3", "age", "21");
        Map<String, String> entries = opsForHash.entries("user3");
        System.out.println("entries = " + entries);
    }

}
