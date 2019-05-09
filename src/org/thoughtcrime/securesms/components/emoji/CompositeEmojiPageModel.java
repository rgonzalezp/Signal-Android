package org.thoughtcrime.securesms.components.emoji;

import android.support.annotation.AttrRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.annimon.stream.Stream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class CompositeEmojiPageModel implements EmojiPageModel {
  @AttrRes  private final int         iconAttr;
  @NonNull  private final EmojiPageModel[] models;

  public CompositeEmojiPageModel(@AttrRes int iconAttr, @NonNull EmojiPageModel... models) {
    this.iconAttr = iconAttr;
    this.models   = models;
  }

  public int getIconAttr() {
    return iconAttr;
  }

  @Override
  public @NonNull List<String> getEmoji() {
    List<String> emojis = new LinkedList<>();
    for(int i = 0; i<models.length; i++) {
      emojis.addAll(models[i].getEmoji());
    }
    return emojis;
  }

  @Override
  public @NonNull List<Emoji> getDisplayEmoji() {
    List<Emoji> emojis = new LinkedList<>();
    for(int i = 0; i<models.length; i++) {
      emojis.addAll(models[i].getDisplayEmoji());
    }
    return emojis;
  }

  @Override
  public boolean hasSpriteMap() {
    return false;
  }

  @Override
  public @Nullable String getSprite() {
    return null;
  }

  @Override
  public boolean isDynamic() {
    return false;
  }
}
