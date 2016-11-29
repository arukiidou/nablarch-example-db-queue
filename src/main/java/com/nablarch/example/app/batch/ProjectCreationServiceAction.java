package com.nablarch.example.app.batch;

import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

import com.nablarch.example.app.entity.Project;

import nablarch.common.dao.UniversalDao;
import nablarch.core.db.connection.AppDbConnection;
import nablarch.core.db.statement.SqlRow;
import nablarch.core.db.transaction.SimpleDbTransactionExecutor;
import nablarch.core.db.transaction.SimpleDbTransactionManager;
import nablarch.core.message.ApplicationException;
import nablarch.core.message.MessageLevel;
import nablarch.core.message.MessageUtil;
import nablarch.core.repository.SystemRepository;
import nablarch.fw.DataReader;
import nablarch.fw.ExecutionContext;
import nablarch.fw.Result;
import nablarch.fw.action.BatchAction;
import nablarch.fw.reader.DatabaseRecordReader;
import nablarch.fw.reader.DatabaseTableQueueReader;

/**
 * 受信テーブルを入力としてプロジェクト情報を作成する常駐サービス。
 *
 * @author Nabu Rakutaro
 */
public class ProjectCreationServiceAction extends BatchAction<SqlRow> {

    /** SQLID */
    private static final String SQL_ID = ProjectCreationServiceAction.class.getName() + '#';

    /** プロセスIDを保持するDTO */
    private static final ProcessDto PROCESS_DTO = new ProcessDto(UUID.randomUUID().toString());

    @Override
    public Result handle(final SqlRow inputData, final ExecutionContext context) {

        final Project project = UniversalDao.findBySqlFile(
                Project.class,
                SQL_ID + "GET_RECEIVED_PROJECT",
                inputData);

        if (!isValidProjectPeriod(project)) {
            throw new ApplicationException(MessageUtil.createMessage(MessageLevel.ERROR, "abnormal.project.period"));
        }

        UniversalDao.insert(project);

        return new Result.Success();
    }

    /**
     * プロジェクトの期間が有効かどうか。
     * <p>
     * 開始が終了より後の場合は無効と判断する。
     *
     * @param project プロジェクト
     * @return プロジェクトの期間が有効または未指定の場合は{@code true}
     */
    private static boolean isValidProjectPeriod(final Project project) {
        if (project == null) {
            return true;
        }
        final Date startDate = project.getProjectStartDate();
        final Date endDate = project.getProjectEndDate();
        if (startDate == null || endDate == null) {
            return true;
        }
        return startDate.compareTo(endDate) <= 0;
    }

    /**
     * 正常終了時には、ステータスを処理済みに更新する。
     */
    @Override
    protected void transactionSuccess(final SqlRow inputData, final ExecutionContext context) {
        updateStatus(inputData, StatusUpdateDto::createNormalEnd);
    }

    /**
     * 異常終了時には、ステータスを異常に更新する。
     */
    @Override
    protected void transactionFailure(final SqlRow inputData, final ExecutionContext context) {
        updateStatus(inputData, StatusUpdateDto::createAbnormalEnd);
    }

    private void updateStatus(final SqlRow inputData, final Function<String, StatusUpdateDto> function) {
        getParameterizedSqlStatement("UPDATE_STATUS")
                .executeUpdateByObject(function.apply(inputData.getString("RECEIVED_MESSAGE_SEQUENCE")));
    }

    @Override
    public DataReader<SqlRow> createReader(final ExecutionContext context) {
        final DatabaseRecordReader databaseRecordReader = new DatabaseRecordReader();
        databaseRecordReader.setStatement(getParameterizedSqlStatement("FIND_RECEIVED_PROJECTS"), PROCESS_DTO);
        databaseRecordReader.setListener(() -> {
            final SimpleDbTransactionManager transactionManager = SystemRepository.get("redundancyTransaction");
            new SimpleDbTransactionExecutor<Void>(transactionManager) {
                @Override
                public Void execute(final AppDbConnection appDbConnection) {
                    appDbConnection
                            .prepareParameterizedSqlStatementBySqlId(SQL_ID + "UPDATE_PROCESS_ID")
                            .executeUpdateByObject(PROCESS_DTO);
                    return null;
                }
            }.doTransaction();

        });
        return new DatabaseTableQueueReader(databaseRecordReader, 1000, "RECEIVED_MESSAGE_SEQUENCE");
    }

    /**
     * ステータスを更新するための情報を持つBean。
     *
     * @author Nabu Rakutaro
     */
    public static final class StatusUpdateDto {

        /** id */
        private final String id;

        /** 更新後のステータス */
        private final String newStatus;

        /**
         * Beanを生成する。
         * @param id ID
         * @param newStatus 更新後のステータス
         */
        public StatusUpdateDto(final String id, final String newStatus) {
            this.id = id;
            this.newStatus = newStatus;
        }

        /**
         * IDを取得する。
         * @return ID
         */
        public String getId() {
            return id;
        }

        /**
         * 更新後のステータスを取得する。
         * @return 更新後のステータス
         */
        public String getNewStatus() {
            return newStatus;
        }

        /**
         * 正常終了を示すオブジェクトを生成する。
         *
         * @param id ID
         * @return 生成したオブジェクト
         */
        private static StatusUpdateDto createNormalEnd(String id) {
            return new StatusUpdateDto(id, "1");
        }

        /**
         * 異常終了を示すオブジェクトを生成する。
         *
         * @param id ID
         * @return 生成したオブジェクト
         */
        private static StatusUpdateDto createAbnormalEnd(String id) {
            return new StatusUpdateDto(id, "2");
        }
    }

    /**
     * プロセスIDを保持するBean。
     *
     * @author Nabu Rakutaro
     */
    public static class ProcessDto {

        /** プロセスID */
        private final String processId;

        /**
         * インスタンスを生成する。
         *
         * @param processId プロセスID
         */
        public ProcessDto(String processId) {
            this.processId = processId;
        }

        /**
         * プロセスIDを取得する。
         *
         * @return プロセスID
         */
        public String getProcessId() {
            return processId;
        }
    }
}

