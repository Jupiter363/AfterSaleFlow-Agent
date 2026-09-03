package com.example.dispute.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.dispute.review.domain.ReviewPacketContentHasher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewPacketContentHasherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hashIsIndependentOfObjectKeyInsertionOrderButSensitiveToContent() {
        Map<String,Object> first=new LinkedHashMap<>();
        first.put("packet_id","PACKET_1");
        first.put("body",Map.of("b",2,"a",1));
        Map<String,Object> reordered=new LinkedHashMap<>();
        reordered.put("body",Map.of("a",1,"b",2));
        reordered.put("packet_id","PACKET_1");

        String contentHash=ReviewPacketContentHasher.hash(mapper,first);

        assertThat(contentHash).matches("[0-9a-f]{64}");
        assertThat(ReviewPacketContentHasher.hash(mapper,reordered)).isEqualTo(contentHash);
        assertThat(ReviewPacketContentHasher.hash(mapper,Map.of("packet_id","PACKET_2")))
                .isNotEqualTo(contentHash);
    }
}
