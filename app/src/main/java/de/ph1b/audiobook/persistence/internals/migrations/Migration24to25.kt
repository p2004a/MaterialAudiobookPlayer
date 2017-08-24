package de.ph1b.audiobook.persistence.internals.migrations

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import de.ph1b.audiobook.injection.App
import de.ph1b.audiobook.persistence.internals.moveToNextLoop
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.ArrayList
import java.util.InvalidPropertiesFormatException

/**
 * Migrate the database so they will be stored as json objects
 */
@SuppressLint("Recycle")
class Migration24to25 : Migration {

  override fun migrate(db: SQLiteDatabase) {
    val copyBookTableName = "TABLE_BOOK_COPY"
    val copyChapterTableName = "TABLE_CHAPTERS_COPY"

    db.execSQL("ALTER TABLE TABLE_BOOK RENAME TO $copyBookTableName")
    db.execSQL("ALTER TABLE TABLE_CHAPTERS RENAME TO $copyChapterTableName")

    val newBookTable = "TABLE_BOOK"
    val CREATE_TABLE_BOOK = "CREATE TABLE $newBookTable ( BOOK_ID INTEGER PRIMARY KEY AUTOINCREMENT, BOOK_JSON TEXT NOT NULL)"
    db.execSQL(CREATE_TABLE_BOOK)

    val bookCursor = db.query(
        copyBookTableName,
        arrayOf("BOOK_ID", "BOOK_ROOT", "BOOK_TYPE"),
        null, null, null, null, null
    )

    bookCursor.moveToNextLoop {
      val bookId = bookCursor.getLong(0)
      val root = bookCursor.getString(1)
      val type = bookCursor.getString(2)

      val mediaCursor = db.query(
          copyChapterTableName, arrayOf("CHAPTER_PATH", "CHAPTER_DURATION", "CHAPTER_NAME"),
          "BOOK_ID" + "=?", arrayOf(bookId.toString()),
          null, null, null
      )
      val chapterNames = ArrayList<String>(mediaCursor.count)
      val chapterDurations = ArrayList<Int>(mediaCursor.count)
      val chapterPaths = ArrayList<String>(mediaCursor.count)
      mediaCursor.moveToNextLoop {
        chapterPaths.add(mediaCursor.getString(0))
        chapterDurations.add(mediaCursor.getInt(1))
        chapterNames.add(mediaCursor.getString(2))
      }

      val configFile = when (type) {
        "COLLECTION_FILE", "SINGLE_FILE" -> File(root, "." + chapterNames[0] + "-map.json")
        "COLLECTION_FOLDER", "SINGLE_FOLDER" -> File(root, "." + (File(root).name) + "-map.json")
        else -> throw InvalidPropertiesFormatException("Upgrade failed due to unknown type=" + type)
      }
      val backupFile = File(configFile.absolutePath + ".backup")

      val configFileValid = configFile.exists() && configFile.canRead() && configFile.length() > 0
      val backupFileValid = backupFile.exists() && backupFile.canRead() && backupFile.length() > 0

      var playingInformation: JSONObject? = null
      try {
        if (configFileValid) {
          configFile.readText(Charsets.UTF_8)
          val retString = configFile.readText(Charsets.UTF_8)
          if (!retString.isEmpty()) {
            playingInformation = JSONObject(retString)
          }
        }
      } catch (e: JSONException) {
        e.printStackTrace()
      } catch (e: IOException) {
        e.printStackTrace()
      }

      try {
        if (playingInformation == null && backupFileValid) {
          val retString = backupFile.readText(Charsets.UTF_8)
          playingInformation = JSONObject(retString)
        }
      } catch (e: IOException) {
        e.printStackTrace()
      } catch (e: JSONException) {
        e.printStackTrace()
      }

      if (playingInformation == null) {
        throw InvalidPropertiesFormatException("Could not fetch information")
      }

      val JSON_TIME = "time"
      val JSON_BOOKMARK_TIME = "time"
      val JSON_BOOKMARK_TITLE = "title"
      val JSON_SPEED = "speed"
      val JSON_NAME = "name"
      val JSON_BOOKMARKS = "bookmarks"
      val JSON_REL_PATH = "relPath"
      val JSON_BOOKMARK_REL_PATH = "relPath"
      val JSON_USE_COVER_REPLACEMENT = "useCoverReplacement"

      var currentTime = 0
      try {
        currentTime = playingInformation.getInt(JSON_TIME)
      } catch (e: JSONException) {
        e.printStackTrace()
      }

      val bookmarkRelPathsUnsafe = ArrayList<String>()
      val bookmarkTitlesUnsafe = ArrayList<String>()
      val bookmarkTimesUnsafe = ArrayList<Int>()
      try {
        val bookmarksJ = playingInformation.getJSONArray(JSON_BOOKMARKS)
        for (i in 0 until bookmarksJ.length()) {
          val bookmarkJ = bookmarksJ.get(i) as JSONObject
          bookmarkTimesUnsafe.add(bookmarkJ.getInt(JSON_BOOKMARK_TIME))
          bookmarkTitlesUnsafe.add(bookmarkJ.getString(JSON_BOOKMARK_TITLE))
          bookmarkRelPathsUnsafe.add(bookmarkJ.getString(JSON_BOOKMARK_REL_PATH))
        }
      } catch (e: JSONException) {
        e.printStackTrace()
        bookmarkRelPathsUnsafe.clear()
        bookmarkTitlesUnsafe.clear()
        bookmarkTimesUnsafe.clear()
      }

      val bookmarkRelPathsSafe = ArrayList<String>()
      val bookmarkTitlesSafe = ArrayList<String>()
      val bookmarkTimesSafe = ArrayList<Int>()

      for (i in bookmarkRelPathsUnsafe.indices) {
        val bookmarkExists = chapterPaths.any { it == bookmarkRelPathsUnsafe[i] }
        if (bookmarkExists) {
          bookmarkRelPathsSafe.add(bookmarkRelPathsUnsafe[i])
          bookmarkTitlesSafe.add(bookmarkTitlesUnsafe[i])
          bookmarkTimesSafe.add(bookmarkTimesUnsafe[i])
        }
      }

      var currentPath = ""
      try {
        currentPath = playingInformation.getString(JSON_REL_PATH)
      } catch (e: JSONException) {
        e.printStackTrace()
      }

      val relPathExists = chapterPaths.contains(currentPath)
      if (!relPathExists) {
        currentPath = chapterPaths.first()
        currentTime = 0
      }

      var speed = 1.0f
      try {
        speed = java.lang.Float.valueOf(playingInformation.getString(JSON_SPEED))
      } catch (e: JSONException) {
        e.printStackTrace()
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }

      var name = ""
      try {
        name = playingInformation.getString(JSON_NAME)
      } catch (e: JSONException) {
        e.printStackTrace()
      }

      if (name.isEmpty()) {
        name = if (chapterPaths.size == 1) {
          val chapterPath = chapterPaths.first()
          chapterPath.substring(0, chapterPath.lastIndexOf("."))
        } else {
          File(root).name
        }
      }

      var useCoverReplacement = false
      try {
        useCoverReplacement = playingInformation.getBoolean(JSON_USE_COVER_REPLACEMENT)
      } catch (e: JSONException) {
        e.printStackTrace()
      }

      try {
        val chapters = JSONArray()
        for (i in chapterPaths.indices) {
          val chapter = JSONObject()
          chapter.put("path", root + File.separator + chapterPaths[i])
          chapter.put("duration", chapterDurations[i])
          chapters.put(chapter)
        }

        val bookmarks = JSONArray()
        for (i in bookmarkRelPathsSafe.indices) {
          val bookmark = JSONObject()
          bookmark.put("mediaPath", root + File.separator + bookmarkRelPathsSafe[i])
          bookmark.put("title", bookmarkTitlesSafe[i])
          bookmark.put("time", bookmarkTimesSafe[i])
          bookmarks.put(bookmark)
        }

        val book = JSONObject()
        book.put("root", root)
        book.put("name", name)
        book.put("chapters", chapters)
        book.put("currentMediaPath", root + File.separator + currentPath)
        book.put("type", type)
        book.put("bookmarks", bookmarks)
        book.put("useCoverReplacement", useCoverReplacement)
        book.put("time", currentTime)
        book.put("playbackSpeed", speed.toDouble())

        Timber.d("upgrade24 restored book=$book")
        val cv = ContentValues()
        cv.put("BOOK_JSON", book.toString())
        val newBookId = db.insert(newBookTable, null, cv)
        book.put("id", newBookId)

        // move cover file if possible
        val coverFile = if (chapterPaths.size == 1) {
          val fileName = "." + chapterNames.first() + ".jpg"
          File(root, fileName)
        } else {
          val fileName = "." + (File(root).name) + ".jpg"
          File(root, fileName)
        }
        if (coverFile.exists() && coverFile.canWrite()) {
          try {
            val newCoverFile = File(
                Environment.getExternalStorageDirectory().absolutePath + File.separator + "Android" + File.separator + "data" + File.separator + App.component.context.packageName,
                newBookId.toString() + ".jpg"
            )
            if (!coverFile.parentFile.exists()) {
              //noinspection ResultOfMethodCallIgnored
              coverFile.parentFile.mkdirs()
            }
            coverFile.copyTo(newCoverFile)
            coverFile.delete()
          } catch (e: IOException) {
            e.printStackTrace()
          }

        }
      } catch (e: JSONException) {
        throw InvalidPropertiesFormatException(e)
      }

    }
  }
}
