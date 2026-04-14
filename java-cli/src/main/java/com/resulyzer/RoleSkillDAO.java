package com.resulyzer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * RoleSkillDAO.java — Data Access Object for the role_skills table.
 *
 * <p>DAO (Data Access Object) is a design pattern where all SQL operations
 * for one entity (here: role_skills) live in a single dedicated class.
 * This keeps database logic out of the business logic in Main.java.
 *
 * <p>Uses a {@link PreparedStatement} to safely pass user-supplied role
 * strings without risk of SQL injection.
 */
public final class RoleSkillDAO {

    private static final Logger logger = Logger.getLogger(RoleSkillDAO.class.getName());

    private static final String SELECT_SKILLS =
            "SELECT skill, weight FROM role_skills WHERE role = ? ORDER BY weight DESC";

    // Utility class — no instances
    private RoleSkillDAO() {}

    /**
     * Retrieves all skills and their weights for the given job role.
     *
     * @param role  Role name exactly as stored in the DB, e.g. "Backend"
     * @return  An ordered map of {@code skill → weight} (ordered by weight DESC).
     *          Empty map if the role is not found or a DB error occurs.
     */
    public static Map<String, Double> getSkillsByRole(String role) {
        Map<String, Double> skills = new LinkedHashMap<>();

        // try-with-resources: Connection and PreparedStatement are auto-closed
        try (Connection con = DBUtil.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_SKILLS)) {

            ps.setString(1, role);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    skills.put(rs.getString("skill"), rs.getDouble("weight"));
                }
            }

            if (skills.isEmpty()) {
                logger.warning("No skills found in DB for role '" + role + "'");
            } else {
                logger.info("Loaded " + skills.size() + " skills for role '" + role + "'");
            }

        } catch (Exception e) {
            logger.severe("DB query failed for role '" + role + "': " + e.getMessage());
        }

        return skills;
    }
}
