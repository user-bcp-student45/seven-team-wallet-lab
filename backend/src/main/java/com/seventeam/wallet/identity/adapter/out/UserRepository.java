package com.seventeam.wallet.identity.adapter.out;

import com.seventeam.wallet.identity.domain.User;
import com.seventeam.wallet.identity.domain.UserStatus;
import com.seventeam.wallet.shared.Hashing;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS = """
            id, email, password_hash, full_name, document_number, phone, phone_verified, phone_code_hash,
            phone_code_expires, phone_code_attempts, status, completed_steps, failed_logins, locked_until""";

    private static final RowMapper<User> MAPPER = UserRepository::map;

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, String email, String passwordHash) {
        jdbc.sql("insert into app_user (id, email, password_hash) values (:id, :email, :hash)")
                .param("id", id).param("email", email).param("hash", passwordHash)
                .update();
    }

    public void insertActiveDemo(UUID id, String email, String passwordHash, String fullName,
                                 String documentNumber, String phone, boolean demoContact) {
        jdbc.sql("""
                insert into app_user (id, email, password_hash, full_name, document_number, phone, phone_verified,
                                      status, completed_steps, demo_contact, activated_at)
                values (:id, :email, :hash, :name, :dni, :phone, true, 'ACTIVE', 4, :contact, now())""")
                .param("id", id).param("email", email).param("hash", passwordHash).param("name", fullName)
                .param("dni", documentNumber).param("phone", phone).param("contact", demoContact)
                .update();
    }

    public Optional<User> findByEmail(String email) {
        return jdbc.sql("select " + COLUMNS + " from app_user where lower(email) = lower(:email)")
                .param("email", email).query(MAPPER).optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app_user where id = :id")
                .param("id", id).query(MAPPER).optional();
    }

    /** Serializa los pasos de onboarding de un mismo usuario. */
    public Optional<User> findByIdForUpdate(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from app_user where id = :id for update")
                .param("id", id).query(MAPPER).optional();
    }

    public void updatePersonalData(UUID id, String fullName, String documentNumber) {
        jdbc.sql("""
                update app_user
                   set full_name = :name, document_number = :dni, completed_steps = greatest(completed_steps, 2)
                 where id = :id""")
                .param("id", id).param("name", fullName).param("dni", documentNumber)
                .update();
    }

    public void storePhoneCode(UUID id, String phone, String codeHash, Instant expires) {
        jdbc.sql("""
                update app_user
                   set phone = :phone, phone_verified = false, phone_code_hash = :hash,
                       phone_code_expires = :expires, phone_code_attempts = 0
                 where id = :id""")
                .param("id", id).param("phone", phone).param("hash", codeHash).param("expires", Hashing.utc(expires))
                .update();
    }

    public void incrementPhoneAttempts(UUID id) {
        jdbc.sql("update app_user set phone_code_attempts = phone_code_attempts + 1 where id = :id")
                .param("id", id).update();
    }

    public void markPhoneVerified(UUID id) {
        jdbc.sql("""
                update app_user
                   set phone_verified = true, phone_code_hash = null, phone_code_expires = null,
                       completed_steps = greatest(completed_steps, 3)
                 where id = :id""")
                .param("id", id).update();
    }

    public void activate(UUID id, Instant now) {
        jdbc.sql("update app_user set status = 'ACTIVE', completed_steps = 4, activated_at = :now where id = :id")
                .param("id", id).param("now", Hashing.utc(now)).update();
    }

    public void registerFailedLogin(UUID id, int maxFailed, Instant lockUntil) {
        jdbc.sql("""
                update app_user
                   set failed_logins = failed_logins + 1,
                       locked_until = case when failed_logins + 1 >= :max then :until else locked_until end
                 where id = :id""")
                .param("id", id).param("max", maxFailed).param("until", Hashing.utc(lockUntil))
                .update();
    }

    public void resetFailedLogins(UUID id) {
        jdbc.sql("update app_user set failed_logins = 0, locked_until = null where id = :id")
                .param("id", id).update();
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("document_number"),
                rs.getString("phone"),
                rs.getBoolean("phone_verified"),
                rs.getString("phone_code_hash"),
                instant(rs, "phone_code_expires"),
                rs.getInt("phone_code_attempts"),
                UserStatus.valueOf(rs.getString("status")),
                rs.getInt("completed_steps"),
                rs.getInt("failed_logins"),
                instant(rs, "locked_until"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
