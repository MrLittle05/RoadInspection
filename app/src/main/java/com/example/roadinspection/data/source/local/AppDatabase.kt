package com.example.roadinspection.data.source.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用全局数据库 (Room Database)。
 *
 * 负责管理数据库连接和实体映射。
 *
 * @property entities 注册所有的数据表实体 ([InspectionTask], [InspectionRecord])
 * @property version 数据库版本号。修改表结构后需递增此版本。
 */
@Database(
    entities = [InspectionTask::class, InspectionRecord::class],
    version = 1,
    exportSchema = false // 开发阶段设为 false，正式版建议开启以导出 Schema JSON
)
abstract class AppDatabase : RoomDatabase() {

    /**
     * 获取巡检模块的 DAO 接口实例。
     */
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例实例。
         *
         * 数据库连接属于重量级资源，整个 App 生命周期内应仅保持一个实例。
         *
         * @param context 上下文
         * @return [AppDatabase] 实例
         */
        fun getDatabase(context: Context): AppDatabase {
            // Double-Checked Locking 单例模式
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "road_inspection.db"
                )
                    // 【开发阶段神器】:
                    // 当检测到表结构变更且未提供 Migration 时，直接清空旧数据重建表。
                    // 警告：上线前必须移除此行，并实现标准的 Migration 逻辑，防止用户数据丢失。
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}