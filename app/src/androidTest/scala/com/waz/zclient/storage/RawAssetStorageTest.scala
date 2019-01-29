/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.storage

import java.net.URI

import android.support.test.filters.MediumTest
import android.support.test.runner.AndroidJUnit4
import com.waz.cache2.CacheService.{AES_CBC_Encryption, NoEncryption}
import com.waz.model._
import com.waz.service.assets2.RawAssetStorage.RawAssetDao
import com.waz.service.assets2.{RawAsset, _}
import com.waz.sync.client.AssetClient2.Retention
import org.junit.runner.RunWith
import org.threeten.bp.Duration

/**
  * Wire
  * Copyright (C) 2018 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
@RunWith(classOf[AndroidJUnit4])
@MediumTest
class RawAssetStorageTest extends GeneralStorageTest(RawAssetDao)(
  entities = Set(
    RawAsset(
      id = RawAssetId(),
      sha = Sha256("ad34da34"),
      encryption = NoEncryption,
      source = URI.create("https://github.com/wireapp/wire-android-sync-engine/pull/437"),
      mime = Mime.Default,
      uploaded = 50,
      size = 1000,
      retention = Retention.Eternal,
      public = true,
      convId = None,
      details = ImageDetails(Dim2(1,2), Medium),
      assetId = None
    ),
    RawAsset(
      id = RawAssetId(),
      sha = Sha256("ad34da34"),
      encryption = NoEncryption,
      source = URI.create("https://github.com/wireapp/wire-android-sync-engine/pull/437"),
      mime = Mime.Default,
      uploaded = 50,
      size = 1000,
      retention = Retention.Eternal,
      public = false,
      convId = None,
      details = AudioDetails(Duration.ofDays(1), Loudness(Vector(0.4f, 0.5f, 0.6f))),
      assetId = Some(AssetId())
    ),
    RawAsset(
      id = RawAssetId(),
      sha = Sha256("ad34da34"),
      encryption = AES_CBC_Encryption(AESKey()),
      source = URI.create("https://github.com/wireapp/wire-android-sync-engine/pull/437"),
      mime = Mime.Default,
      uploaded = 50,
      size = 1000,
      retention = Retention.Eternal,
      public = true,
      convId = None,
      details = VideoDetails(Dim2(1,2), Duration.ofDays(1)),
      assetId = None
    )
  ),
  idExtractor = _.id
)
