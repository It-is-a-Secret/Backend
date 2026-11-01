package com.blursome.blursome.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.blursome.blursome.chat.domain.ChatRoom;
import com.blursome.blursome.chat.domain.ChatRoomMember;
import com.blursome.blursome.chat.domain.ChatRoomStatus;
import com.blursome.blursome.global.persistence.JpaAuditingConfig;
import com.blursome.blursome.member.domain.Member;
import com.blursome.blursome.member.domain.OAuthProvider;
import com.blursome.blursome.support.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** {@link ChatRoomMemberRepository#findPartnerRoomRelations}를 MySQL(Testcontainers)로 검증한다(#112). */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class ChatRoomMemberRepositoryTest {

  @Autowired
  private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired
  private TestEntityManager em;

  private Member persistMember(String suffix) {
    Member member = Member.createOAuthMember(
        OAuthProvider.KAKAO, "pid-" + suffix, "name-" + suffix, suffix + "@test.com", null);
    em.persist(member);
    return member;
  }

  private ChatRoom persistRoom(Member a, Member b, ChatRoomMember[] memberships) {
    ChatRoom room = ChatRoom.createOnMatched(a.getId(), b.getId());
    em.persist(room);
    memberships[0] = ChatRoomMember.join(room, a);
    memberships[1] = ChatRoomMember.join(room, b);
    em.persist(memberships[0]);
    em.persist(memberships[1]);
    return room;
  }

  @Test
  @DisplayName("내가 참여했던 모든 방의 상대·방·상태를 주고, 내가 나간 방도 포함한다")
  void findPartnerRoomRelations_includesRoomsIHaveLeft() {
    Member me = persistMember("me");
    Member activePartner = persistMember("active");
    Member leftPartner = persistMember("left");
    Member stranger1 = persistMember("s1");
    Member stranger2 = persistMember("s2");
    ChatRoomMember[] activeMemberships = new ChatRoomMember[2];
    ChatRoom activeRoom = persistRoom(me, activePartner, activeMemberships);
    ChatRoomMember[] leftMemberships = new ChatRoomMember[2];
    ChatRoom leftRoom = persistRoom(me, leftPartner, leftMemberships);
    leftMemberships[0].leave(); // 내가 나갔다
    leftRoom.close();
    persistRoom(stranger1, stranger2, new ChatRoomMember[2]); // 나와 무관한 방
    em.flush();
    em.clear();

    List<PartnerRoomRelation> relations = chatRoomMemberRepository.findPartnerRoomRelations(me.getId());

    assertThat(relations)
        .extracting(PartnerRoomRelation::getPartnerId, PartnerRoomRelation::getRoomId,
            PartnerRoomRelation::getRoomStatus)
        .containsExactlyInAnyOrder(
            tuple(activePartner.getId(), activeRoom.getId(), ChatRoomStatus.ACTIVE),
            tuple(leftPartner.getId(), leftRoom.getId(), ChatRoomStatus.CLOSED));
  }
}
