package com.cloudqueryx.web;

import com.cloudqueryx.web.auth.*;
import com.cloudqueryx.web.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class WebApiTest {

    @Test
    void passwordHashAndVerify() {
        String hash = PasswordHasher.hash("testpassword");
        assertThat(PasswordHasher.verify("testpassword", hash)).isTrue();
        assertThat(PasswordHasher.verify("wrongpassword", hash)).isFalse();
    }

    @Test
    void userRegistrationAndAuth() {
        UserStore store = new UserStore();
        UserStore.User user = store.register("test@example.com", "password123");
        assertThat(user.email()).isEqualTo("test@example.com");
        assertThat(user.id()).isNotNull();

        UserStore.User auth = store.authenticate("test@example.com", "password123");
        assertThat(auth).isNotNull();
        assertThat(auth.id()).isEqualTo(user.id());

        assertThat(store.authenticate("test@example.com", "wrong")).isNull();
        assertThat(store.authenticate("nobody@example.com", "password123")).isNull();
    }

    @Test
    void duplicateRegistrationThrows() {
        UserStore store = new UserStore();
        store.register("test@example.com", "password123");
        assertThatThrownBy(() -> store.register("test@example.com", "other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionManagement() {
        SessionManager mgr = new SessionManager();
        String token = mgr.createSession("user1", "test@test.com");
        assertThat(token).isNotNull();

        SessionManager.Session session = mgr.validate(token);
        assertThat(session).isNotNull();
        assertThat(session.userId()).isEqualTo("user1");

        mgr.invalidate(token);
        assertThat(mgr.validate(token)).isNull();
    }

    @Test
    void databaseOwnershipIsolation() {
        DatabaseManager mgr = new DatabaseManager();
        UserDatabase db1 = mgr.create("user1", "MyDB");
        UserDatabase db2 = mgr.create("user2", "TheirDB");

        assertThat(mgr.get(db1.getId(), "user1")).isNotNull();
        assertThat(mgr.get(db1.getId(), "user2")).isNull();

        assertThat(mgr.listForUser("user1")).hasSize(1);
        assertThat(mgr.listForUser("user2")).hasSize(1);

        assertThat(mgr.delete(db2.getId(), "user1")).isFalse();
        assertThat(mgr.delete(db2.getId(), "user2")).isTrue();
        assertThat(mgr.listForUser("user2")).isEmpty();
    }

    @Test
    void userDatabaseHasAllComponents() {
        DatabaseManager mgr = new DatabaseManager();
        UserDatabase db = mgr.create("user1", "TestDB");

        assertThat(db.getCatalog()).isNotNull();
        assertThat(db.getVectorStore()).isNotNull();
        assertThat(db.getMemoryStore()).isNotNull();
        assertThat(db.getSemanticGraph()).isNotNull();
        assertThat(db.getMultimodalStore()).isNotNull();
        assertThat(db.getEventStore()).isNotNull();
    }
}
