package tech.powerjob.worker.persistence;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import tech.powerjob.worker.common.constants.TaskStatus;
import tech.powerjob.worker.core.processor.TaskResult;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.AllArgsConstructor;

import java.sql.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 任务持久化实现层，表名：task_info
 *
 * @author tjq
 * @since 2020/3/17
 */
@Data
@AllArgsConstructor
public class TaskDAOImpl implements TaskDAO {
    
    private final ConnectionFactory connectionFactory;

    @Override
    public void initTable() throws Exception {

        String delTableSQL = "drop table if exists task_info";
        // 感谢 Gitee 用户 @Linfly 反馈的 BUG
        // bigint(20) 与 Java Long 取值范围完全一致
        String createTableSQL = "create table task_info (task_id varchar(255), instance_id bigint(20), sub_instance_id bigint(20), task_name varchar(255), task_content blob, address varchar(255), status int(5), result text, failed_cnt int(11), created_time bigint(20), last_modified_time bigint(20), last_report_time bigint(20), unique KEY pkey (instance_id, task_id))";

        try (Connection conn = connectionFactory.getConnection(); Statement stat = conn.createStatement()) {
            stat.execute(delTableSQL);
            stat.execute(createTableSQL);
        }
    }

    @Override
    public boolean save(TaskDO task) throws SQLException {
        String insertSQL = "insert into task_info(task_id, instance_id, sub_instance_id, task_name, task_content, address, status, result, failed_cnt, created_time, last_modified_time, last_report_time) values (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement preparedState = conn.prepareStatement(insertSQL)) {
            fillInsertPreparedStatement(task, preparedState);
            return preparedState.executeUpdate() == 1;
        }
    }

    @Override
    public boolean batchSave(Collection<TaskDO> tasks) throws SQLException {
        String insertSQL = "insert into task_info(task_id, instance_id, sub_instance_id, task_name, task_content, address, status, result, failed_cnt, created_time, last_modified_time, last_report_time) values (?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement preparedState = conn.prepareStatement(insertSQL)) {

            for (TaskDO task : tasks) {

                fillInsertPreparedStatement(task, preparedState);
                preparedState.addBatch();
            }

            preparedState.executeBatch();
            return true;

        }
    }


    @Override
    public boolean simpleDelete(SimpleTaskQuery condition) throws SQLException {
        String deleteSQL = "delete from task_info where %s";
        String sql = String.format(deleteSQL, condition.getQueryCondition());
        try (Connection conn = connectionFactory.getConnection(); Statement stat = conn.createStatement()) {
            stat.executeUpdate(sql);
            return true;
        }
    }

    @Override
    public List<TaskDO> simpleQuery(SimpleTaskQuery query) throws SQLException {
        ResultSet resultSetTask = null;
        String sql = "select * from task_info where " + query.getQueryCondition();
        List<TaskDO> result = Lists.newLinkedList();
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            resultSetTask = ps.executeQuery();
            while (resultSetTask.next()) {
                result.add(convert(resultSetTask));
            }
        } finally {
            if (resultSetTask != null) {
                try {
                    resultSetTask.close();
                }catch (Exception ignore) {
                }
            }
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> simpleQueryPlus(SimpleTaskQuery query) throws SQLException {
        ResultSet resultSetTask = null;
        String sqlFormat = "select %s from task_info where %s";
        String sql = String.format(sqlFormat, query.getQueryContent(), query.getQueryCondition());
        List<Map<String, Object>> result = Lists.newLinkedList();
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement preparedState = conn.prepareStatement(sql)) {
            resultSetTask = preparedState.executeQuery();
            // 原数据，包含了列名
            ResultSetMetaData  metaData = resultSetTask.getMetaData();
            while (resultSetTask.next()) {
                Map<String, Object> row = Maps.newHashMap();
                result.add(row);

                for (int i = 0; i < metaData.getColumnCount(); i++) {
                    String colName = metaData.getColumnName(i + 1);
                    Object colValue = resultSetTask.getObject(colName);
                    row.put(colName, colValue);
                }
            }
        } finally {
            if (resultSetTask != null) {
                try {
                    resultSetTask.close();
                }catch (Exception ignore) {
                }
            }
        }
        return result;
    }

    @Override
    public boolean simpleUpdate(SimpleTaskQuery condition, TaskDO updateField) throws SQLException {
        String sqlFormat = "update task_info set %s where %s";
        String updateSQL = String.format(sqlFormat, updateField.getUpdateSQL(), condition.getQueryCondition());
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement stat = conn.prepareStatement(updateSQL)) {
            stat.executeUpdate();
            return true;
        }
    }

    @Override
    public List<TaskResult> getAllTaskResult(Long instanceId, Long subInstanceId) throws SQLException {
        ResultSet resultSetTask = null;
        List<TaskResult> taskResults = Lists.newLinkedList();
        String sql = "select task_id, status, result from task_info where instance_id = ? and sub_instance_id = ?";
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement preparedState = conn.prepareStatement(sql)) {
            preparedState.setLong(1, instanceId);
            preparedState.setLong(2, subInstanceId);
            resultSetTask = preparedState.executeQuery();
            while (resultSetTask.next()) {

                int taskStatus = resultSetTask.getInt(2);

                // 只需要完成的结果
                if (taskStatus == TaskStatus.WORKER_PROCESS_SUCCESS.getValue() || taskStatus == TaskStatus.WORKER_PROCESS_FAILED.getValue()) {
                    TaskResult result = new TaskResult();
                    taskResults.add(result);

                    result.setTaskId(resultSetTask.getString(1));
                    result.setSuccess(taskStatus == TaskStatus.WORKER_PROCESS_SUCCESS.getValue());
                    result.setResult(resultSetTask.getString(3));
                }
            }
        }finally {
            if (resultSetTask != null) {
                try {
                    resultSetTask.close();
                }catch (Exception ignore) {
                }
            }
        }
        return taskResults;
    }

    @Override
    public boolean updateTaskStatus(Long instanceId, String taskId, int status, long lastReportTime, String result) throws SQLException {
        String sql = "update task_info set status = ?, last_report_time = ?, result = ?, last_modified_time = ? where instance_id = ? and task_id = ?";
        try (Connection conn = connectionFactory.getConnection(); PreparedStatement preparedState = conn.prepareStatement(sql)) {

            preparedState.setInt(1, status);
            preparedState.setLong(2, lastReportTime);
            preparedState.setString(3, result);
            preparedState.setLong(4, lastReportTime);
            preparedState.setLong(5, instanceId);
            preparedState.setString(6, taskId);
            preparedState.executeUpdate();
            return true;
        }
    }

    private static TaskDO convert(ResultSet rs) throws SQLException {
        TaskDO task = new TaskDO();
        task.setTaskId(rs.getString("task_id"));
        task.setInstanceId(rs.getLong("instance_id"));
        task.setSubInstanceId(rs.getLong("sub_instance_id"));
        task.setTaskName(rs.getString("task_name"));
        task.setTaskContent(rs.getBytes("task_content"));
        task.setAddress(rs.getString("address"));
        task.setStatus(rs.getInt("status"));
        task.setResult(rs.getString("result"));
        task.setFailedCnt(rs.getInt("failed_cnt"));
        task.setCreatedTime(rs.getLong("created_time"));
        task.setLastModifiedTime(rs.getLong("last_modified_time"));
        task.setLastReportTime(rs.getLong("last_report_time"));
        return task;
    }

    // 填充插入字段
    private static void fillInsertPreparedStatement(TaskDO task, PreparedStatement preparedState) throws SQLException {
        preparedState.setString(1, task.getTaskId());
        preparedState.setLong(2, task.getInstanceId());
        preparedState.setLong(3, task.getSubInstanceId());
        preparedState.setString(4, task.getTaskName());
        preparedState.setBytes(5, task.getTaskContent());
        preparedState.setString(6, task.getAddress());
        preparedState.setInt(7, task.getStatus());
        preparedState.setString(8, task.getResult());
        preparedState.setInt(9, task.getFailedCnt());
        preparedState.setLong(10, task.getCreatedTime());
        preparedState.setLong(11, task.getLastModifiedTime());
        preparedState.setLong(12, task.getLastReportTime());
    }


}
