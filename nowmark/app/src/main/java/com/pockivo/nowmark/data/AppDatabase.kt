package com.pockivo.nowmark.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun verifyToString(v: VerifyStatus): String = v.name
    @TypeConverter fun stringToVerify(v: String): VerifyStatus =
        VerifyStatus.entries.firstOrNull { it.name == v } ?: VerifyStatus.UNVERIFIED

    @TypeConverter fun sourceToString(v: MetadataSource): String = v.name
    @TypeConverter fun stringToSource(v: String): MetadataSource =
        MetadataSource.entries.firstOrNull { it.name == v } ?: MetadataSource.APP

    @TypeConverter fun categoryToString(v: MarkCategory): String = v.name
    @TypeConverter fun stringToCategory(v: String): MarkCategory =
        MarkCategory.entries.firstOrNull { it.name == v } ?: MarkCategory.IMPORTANT

    @TypeConverter fun healthToString(v: HealthType): String = v.name
    @TypeConverter fun stringToHealth(v: String): HealthType =
        HealthType.entries.firstOrNull { it.name == v } ?: HealthType.LOW_INPUT
}

@Database(
    entities = [RecordingEntity::class, MarkEntity::class, NoteEntity::class, HealthEventEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): NowmarkDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "nowmark.db").build()
    }
}
