package com.litesuits.orm.db.impl;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import com.litesuits.android.log.Log;
import com.litesuits.orm.db.TableManager;
import com.litesuits.orm.db.assit.Checker;
import com.litesuits.orm.db.assit.Querier;
import com.litesuits.orm.db.assit.Querier.CursorParser;
import com.litesuits.orm.db.assit.SQLBuilder;
import com.litesuits.orm.db.assit.Transaction;
import com.litesuits.orm.db.model.EntityTable;
import com.litesuits.orm.db.model.MapInfo;
import com.litesuits.orm.db.model.MapInfo.MapTable;
import com.litesuits.orm.db.model.Property;
import com.litesuits.orm.db.utils.ClassUtil;
import com.litesuits.orm.db.utils.DataUtil;
import com.litesuits.orm.db.utils.FieldUtil;
import com.litesuits.orm.db.utils.TableUtil;

import java.io.Serializable;
import java.util.*;

/**
 * sql语句构造与执行
 *
 * @author mty
 * @date 2013-6-14下午7:48:34
 */
public class SQLStatement implements Serializable {
    private static final long   serialVersionUID = -3790876762607683712L;
    private static final String TAG              = SQLStatement.class.getSimpleName();
    public static final  short  NONE             = -1;
    /**
     * sql语句
     */
    public String          sql;
    /**
     * sql语句中占位符对应的参数
     */
    public Object[]        bindArgs;
    /**
     * sql语句执行者，私有(private)。
     */
    public SQLiteStatement mStatement;

    //	/**
    //	 * 持久化映射关系的SQL语句（多对一、一对多、多对多）
    //	 */
    //	public ArrayList<SQLStatement> mMappingList;

    /**
     * 实体表，insert时用到它，以判断主键类型；query时用到，以注入实体信息。
     * 减少依赖，废弃之
     */
    // public EntityTable mEntityTable;

    /**
     * 实体类型，query时用到它，以构造对象。
     * 减少依赖，废弃之
     */
    // public Class<?> mEntityClass;
    public SQLStatement() {}

    public SQLStatement(String sql, Object[] args) {
        this.sql = sql;
        this.bindArgs = args;
    }

    /**
     * 给sql语句的占位符(?)按序绑定值
     *
     * @param i
     * @param o
     * @throws Exception
     */
    public void bind(int i, Object o) throws Exception {
        switch (DataUtil.getType(o)) {
            case DataUtil.FIELD_TYPE_NULL:
                mStatement.bindNull(i);
                break;
            case DataUtil.FIELD_TYPE_STRING:
                mStatement.bindString(i, String.valueOf(o));
                break;
            case DataUtil.FIELD_TYPE_LONG:
                mStatement.bindLong(i, ((Number) o).longValue());
                break;
            case DataUtil.FIELD_TYPE_REAL:
                mStatement.bindDouble(i, ((Number) o).doubleValue());
                break;
            case DataUtil.FIELD_TYPE_DATE:
                mStatement.bindLong(i, ((Date) o).getTime());
                break;
            case DataUtil.FIELD_TYPE_BLOB:
                mStatement.bindBlob(i, (byte[]) o);
                break;
            case DataUtil.FIELD_TYPE_SERIALIZABLE:
                mStatement.bindBlob(i, DataUtil.objectToByte(o));
                break;
            default:
                break;
        }
    }

    /**
     * 执行插入单个数据，返回rawid
     *
     * @param db
     * @param entity
     * @return
     * @throws Exception
     */
    public long execInsertWithMapping(SQLiteDatabase db, Object entity) throws Exception {
        printSQL();
        mStatement = db.compileStatement(sql);
        long rowID = NONE;
        Object keyObj = null;
        if (!Checker.isEmpty(bindArgs)) {
            keyObj = bindArgs[0];
            for (int i = 0; i < bindArgs.length; i++) {
                bind(i + 1, bindArgs[i]);
            }
        }
        rowID = mStatement.executeInsert();
        clearArgs();
        if (Log.isPrint) Log.d(TAG, "SQL Execute Insert --> " + rowID);
        if (entity != null) {
            EntityTable table = TableUtil.getTable(entity);
            FieldUtil.setKeyValueIfneed(entity, table.key, keyObj, rowID);
            // 插入关系映射
            final MapInfo mapTable = SQLBuilder.buildMappingSql(entity, false);
            if (mapTable != null && !mapTable.isEmpty()) {
                Transaction.execute(db, new Transaction.Worker<Boolean>() {
                    @Override
                    public Boolean doTransaction(SQLiteDatabase db) throws Exception {
                        for (MapTable table : mapTable.tableList) {
                            TableManager.getInstance().checkOrCreateMappingTable(db, table.name, table.column1,
                                    table.column2);
                        }
                        if (mapTable.delOldRelationSQL != null) for (SQLStatement st : mapTable.delOldRelationSQL) {
                            long rowId = st.execDelete(db);
                            if (Log.isPrint) Log.i(TAG, "Exec delete mapping success, nums: " + rowId);
                        }
                        if (mapTable.mapNewRelationSQL != null) for (SQLStatement st : mapTable.mapNewRelationSQL) {
                            long rowId = st.execInsert(db);
                            if (Log.isPrint) Log.i(TAG, "Exec save mapping success, nums: " + rowId);
                        }
                        return true;
                    }
                });
            }
        }

        return rowID;
    }

    /**
     * 用于给对象持久化映射关系时，不可以注入ID。
     *
     * @param db
     * @return
     * @throws Exception
     */
    public long execInsert(SQLiteDatabase db) throws Exception {
        return execInsertWithMapping(db, null);
    }

    /**
     * 执行批量插入
     *
     * @param db
     * @return
     */
    public int execInsertCollection(SQLiteDatabase db, Collection<?> list) {
        mStatement = db.compileStatement(sql);
        db.beginTransaction();
        if (Log.isPrint) Log.i(TAG, "----> BeginTransaction");
        try {
            Iterator<?> it = list.iterator();
            boolean isMapTableChecked = false;
            EntityTable table = null;
            while (it.hasNext()) {
                mStatement.clearBindings();
                Object obj = it.next();

                if (table == null) {
                    table = TableUtil.getTable(obj);
                    TableManager.getInstance().checkOrCreateTable(db, obj);
                }

                int j = 1;
                Object keyObj = null;
                if (table.key != null) {
                    keyObj = FieldUtil.getAssignedKeyObject(table.key, obj);
                    bind(j++, keyObj);
                }
                if (!Checker.isEmpty(table.pmap)) {
                    // 第一个是主键。其他属性从2开始。
                    for (Property p : table.pmap.values()) {
                        bind(j++, FieldUtil.get(p.field, obj));
                    }
                }
                long rowID = mStatement.executeInsert();
                FieldUtil.setKeyValueIfneed(obj, table.key, keyObj, rowID);

                MapInfo mapTable = SQLBuilder.buildMappingSql(obj, false);
                if (mapTable != null && !mapTable.isEmpty()) {
                    if (!isMapTableChecked) {
                        for (MapTable mt : mapTable.tableList) {
                            TableManager.getInstance().checkOrCreateMappingTable(db, mt.name, mt.column1, mt.column2);
                        }
                        isMapTableChecked = true;
                    }
                    if (mapTable.delOldRelationSQL != null) for (SQLStatement st : mapTable.delOldRelationSQL) {
                        st.execDelete(db);
                    }
                    if (mapTable.mapNewRelationSQL != null) for (SQLStatement st : mapTable.mapNewRelationSQL) {
                        st.execInsert(db);
                    }
                }
            }
            if (Log.isPrint) Log.d(TAG, "Exec insert " + list.size() + " rows , SQL: " + sql);
            db.setTransactionSuccessful();
            if (Log.isPrint) Log.i(TAG, "----> BeginTransaction Successful");
            return list.size();
        } catch (Exception e) {
            if (Log.isPrint) Log.e(TAG, "----> BeginTransaction Failling");
            e.printStackTrace();
        } finally {
            clearArgs();
            db.endTransaction();
        }
        return NONE;
    }

    /**
     * 删除语句执行，返回受影响的行数
     *
     * @param db
     * @return
     * @throws Exception
     */
    public int execDelete(SQLiteDatabase db) throws Exception {
        return execDeleteWithMapping(db, null);
    }

    /**
     * 执行删操作.(excute delete ...)，返回受影响的行数
     * 并将关系映射删除
     *
     * @param db
     * @throws Exception
     */
    public int execDeleteWithMapping(final SQLiteDatabase db, Object entity) throws Exception {
        printSQL();
        mStatement = db.compileStatement(sql);
        if (bindArgs != null) {
            for (int i = 0; i < bindArgs.length; i++) {
                bind(i + 1, bindArgs[i]);
            }
        }
        int nums = mStatement.executeUpdateDelete();
        if (Log.isPrint) Log.d(TAG, "SQL Execute Delete --> " + nums);
        clearArgs();
        if (entity != null) {
            // 删除关系映射
            final MapInfo mapTable = SQLBuilder.buildMappingSql(entity, true);
            if (mapTable != null && !mapTable.isEmpty()) {
                Transaction.execute(db, new Transaction.Worker<Boolean>() {
                    @Override
                    public Boolean doTransaction(SQLiteDatabase db) throws Exception {
                        //for (MapTable table : mapTable.tableList) {
                        //    TableManager.getInstance().checkOrCreateMappingTable(db, table.name, table.column1,
                        //            table.column2);
                        //}
                        if (mapTable.delOldRelationSQL != null) for (SQLStatement st : mapTable.delOldRelationSQL) {
                            long rowId = st.execDelete(db);
                            if (Log.isPrint) Log.i(TAG, "Exec delete mapping success, nums: " + rowId);
                        }
                        return true;
                    }
                });
            }

        }
        return nums;
    }

    /**
     * 执行删操作.(excute delete ...)，返回受影响的行数
     * 并将关系映射删除
     *
     * @param db
     * @throws Exception
     */
    public int execDeleteCollection(final SQLiteDatabase db, final Collection<?> collection) throws Exception {
        printSQL();
        // 删除全部数据
        mStatement = db.compileStatement(sql);
        if (bindArgs != null) {
            for (int i = 0; i < bindArgs.length; i++) {
                bind(i + 1, bindArgs[i]);
            }
        }
        int nums = mStatement.executeUpdateDelete();
        if (Log.isPrint) Log.d(TAG, "SQL Execute Delete --> " + nums);
        clearArgs();
        // 删除关系映射
        MapInfo mapTable = SQLBuilder.buildMappingSql(collection.iterator().next(), true);
        if (mapTable != null && !mapTable.isEmpty()) {
            Boolean suc = Transaction.execute(db, new Transaction.Worker<Boolean>() {
                @Override
                public Boolean doTransaction(SQLiteDatabase db) throws Exception {
                    for (Object o : collection) {
                        MapInfo mapTable = SQLBuilder.buildMappingSql(o, true);
                        if (mapTable.delOldRelationSQL != null) for (SQLStatement st : mapTable.delOldRelationSQL) {
                            long rowId = st.execDelete(db);
                        }
                    }
                    return true;
                }
            });
            if (Log.isPrint) Log.i(TAG, "Exec delete collection mapping: " + ((suc != null && suc) ? "成功" : "失败"));
        } else {
            Log.i(TAG, "此对象组不包含关系映射");
        }
        return nums;
    }

    /**
     * 执行create,drop table 等
     *
     * @param db
     */
    public boolean execute(SQLiteDatabase db) {
        printSQL();
        mStatement = db.compileStatement(sql);
        try {
            if (bindArgs != null) {
                for (int i = 0; i < bindArgs.length; i++) {
                    bind(i + 1, bindArgs[i]);
                }
            }
            mStatement.execute();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            clearArgs();
        }
        return false;
    }

    /**
     * Execute a statement that returns a 1 by 1 table with a numeric value.
     * For example, SELECT COUNT(*) FROM table;
     *
     * @param db
     * @param claxx
     * @return
     */
    public long queryForLong(SQLiteDatabase db, final Class<?> claxx) {
        printSQL();
        mStatement = db.compileStatement(sql);
        long count = mStatement.simpleQueryForLong();
        if (Log.isPrint) Log.d(TAG, "SQL Execute queryForLong --> " + count);
        clearArgs();
        return count;
    }

    /**
     * 执行查询
     * 根据类信息读取数据库，取出全部本类的对象。
     *
     * @param claxx
     * @return
     */
    public <T> ArrayList<T> query(SQLiteDatabase db, final Class<T> claxx) {
        printSQL();
        final ArrayList<T> list = new ArrayList<T>();
        try {
            final EntityTable table = TableUtil.getTable(claxx, false);
            Querier.doQuery(db, this, new CursorParser() {
                @Override
                public void parseEachCursor(SQLiteDatabase db, Cursor c) throws Exception {
                    T t = ClassUtil.newInstance(claxx);
                    DataUtil.injectDataToObject(c, t, table);
                    list.add(t);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    private void printSQL() {
        if (Log.isPrint) Log.d(TAG, "SQL Execute: [" + sql + "] ARGS--> " + Arrays.toString(bindArgs));
    }

    private void clearArgs() {
        if (mStatement != null) mStatement.close();
        sql = null;
        bindArgs = null;
        mStatement = null;
    }

    @Override
    public String toString() {
        return "SQLStatement [sql=" + sql + ", bindArgs=" + Arrays.toString(bindArgs) + ", mStatement=" + mStatement
                + "]";
    }

}
