package org.cheat.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cheat.client.Card.Rank;
import org.cheat.client.Card.Suit;
import org.cheat.client.GameApi.Delete;
import org.cheat.client.GameApi.Operation;
import org.cheat.client.GameApi.Set;
import org.cheat.client.GameApi.SetVisibility;
import org.cheat.client.GameApi.Shuffle;
import org.cheat.client.GameApi.VerifyMove;
import org.cheat.client.GameApi.VerifyMoveDone;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class CheatLogic {
  /* The entries used in the cheat game are:
   *   turn:W/B, isCheater:yes, W, B, M, claim, C0...C51
   * When we send operations on these keys, it will always be in the above order.
   */
  private static final String TURN = "turn"; // turn of which player (either W or B)
  private static final String W = "W"; // White hand
  private static final String B = "B"; // Black hand
  private static final String M = "M"; // Middle pile
  private static final String C = "C"; // Card key (C0...C51)
  private static final String CLAIM = "claim"; // a claim has the form: [3cards, rankK]
  private static final String IS_CHEATER = "isCheater"; // we claim we have a cheater
  private static final String YES = "yes"; // we claim we have a cheater

  public VerifyMoveDone verify(VerifyMove verifyMove) {
    try {
      checkMoveIsLegal(verifyMove);
      return new VerifyMoveDone();
    } catch (Exception e) {
      return new VerifyMoveDone(verifyMove.getLastMovePlayerId(), e.getMessage());
    }
  }

  void checkMoveIsLegal(VerifyMove verifyMove) {
    List<Operation> lastMove = verifyMove.getLastMove();
    Map<String, Object> lastState = verifyMove.getLastState();
    // Checking the operations are as expected.
    List<Operation> expectedOperations = getExpectedOperations(
        lastState, lastMove, verifyMove.getPlayerIds());
    check(expectedOperations.equals(lastMove), expectedOperations, lastMove);

    // Checking the right player did the move.
    Color gotMoveFromColor =
        Color.values()[verifyMove.getPlayerIndex(verifyMove.getLastMovePlayerId())];
    check(gotMoveFromColor == getExpectedMoveFromColor(lastState), gotMoveFromColor);
  }

  /** Returns the color that should make the move. */
  Color getExpectedMoveFromColor(Map<String, Object> lastState) {
    if (lastState.isEmpty()) {
      return Color.W;
    }
    return Color.valueOf((String) lastState.get(TURN));
  }

  @SuppressWarnings("unchecked")
  List<Operation> getExpectedOperations(
      Map<String, Object> lastApiState, List<Operation> lastMove, List<Integer> playerIds) {
    if (lastApiState.isEmpty()) {
      return getInitialMove(playerIds.get(0), playerIds.get(1));
    }
    CheatState lastState = gameApiStateToCheatState(lastApiState);
    List<Integer> lastM = lastState.getMiddle();
    Color turnOfColor = lastState.getTurn();
    // There are 3 types of moves:
    // 1) doing a claim.
    // 2) claiming a cheater (then we have Set(isCheater, yes)).
    // 3) checking if we had a cheater (then we have Delete(isCheater)).
    List<Operation> expectedOperations;
    if (lastMove.contains(new Set(IS_CHEATER, YES))) {
      // claiming a cheater.
      check(!lastState.isCheater());
      // Suppose that W claims B is a cheater:
      // 0) new Set(TURN, W),
      // 1) new Set(IS_CHEATER, YES),
      // 2) new SetVisibility("CX0") ... new SetVisibility("CXn")
      expectedOperations = Lists.newArrayList();
      expectedOperations.add(new Set(TURN, turnOfColor.name()));
      expectedOperations.add(new Set(IS_CHEATER, YES));
      for (Integer cardIndex : lastState.getMiddle()) {
        expectedOperations.add(new SetVisibility(C + cardIndex));
      }

    } else if (lastMove.contains(new Delete(IS_CHEATER))) {
      // checking if we had a cheater.
      check(lastState.isCheater());
      // Suppose that B "lost" (either B cheated or B made a claim and W didn't cheat)
      // then the operations are to move all the cards to B:
      // 0) new Set(TURN, B),
      // 1) new Delete(IS_CHEATER),
      // 2) new Set(B, [...]),
      // 3) new Set(M, ImmutableList.of()),
      // 4) new SetVisibility(CX0, visibleToB) ... new SetVisibility(CXn, visibleToB),
      // 5+n) new Shuffle([...])
      // Let's determine who "lost", just by looking at the lastState.
      Claim lastClaim = lastState.getClaim().get();
      Color possibleCheaterColor = turnOfColor.getOppositeColor();
      List<Integer> cardsIndicesToCheck =
          lastM.subList(lastM.size() - lastClaim.getNumberOfCards(), lastM.size());
      boolean isCheater = false;
      for (Integer cardIndexToCheck : cardsIndicesToCheck) {
        Card card = lastState.getCards().get(cardIndexToCheck).get();
        if (card.getRank() != lastClaim.getCardRank()) {
          isCheater = true;
          break;
        }
      }
      Color loserColor = isCheater ? possibleCheaterColor : possibleCheaterColor.getOppositeColor();
      List<Integer> loserCardIndices =
          loserColor.isWhite() ? lastState.getWhite() : lastState.getBlack();
      List<Integer> loserNewCardIndices = concat(loserCardIndices, lastState.getMiddle());
      expectedOperations = Lists.newArrayList();
      expectedOperations.add(new Set(TURN, loserColor.name()));
      expectedOperations.add(new Delete(IS_CHEATER));
      expectedOperations.add(new Set(loserColor.name(), loserNewCardIndices));
      expectedOperations.add(new Set(M, ImmutableList.of()));
      for (Integer cardIndex : lastState.getMiddle()) {
        expectedOperations.add(new SetVisibility(C + cardIndex,
            ImmutableList.of(playerIds.get(loserColor.ordinal()))));
      }
      List<String> loserNewCards = Lists.newArrayList();
      for (Integer newCardIndex : loserNewCardIndices) {
        loserNewCards.add(C + newCardIndex);
      }
      expectedOperations.add(new Shuffle(loserNewCards));
    } else {
      // doing a claim.
      check(!lastState.isCheater());
      // If W is doing the claim then the format must be:
      // 0) new Set(turn, B),
      // 1) new Set(W, [...]),
      // 2) new Set(M, [...]),
      // 3) new Set(claim, ...)
      // And for B it will be the opposite
      check(lastMove.size() == 4, lastMove);
      Set setWorB = (Set) lastMove.get(1);
      List<Integer> newWorB = (List<Integer>) setWorB.getValue();
      Set setM = (Set) lastMove.get(2);
      List<Integer> newM = (List<Integer>) setM.getValue();
      Set setClaim = (Set) lastMove.get(3);
      Claim claim =
          checkNotNull(Claim.fromClaimEntryInGameState((List<String>) setClaim.getValue()));
      check(claim.getNumberOfCards() >= 1 && claim.getNumberOfCards() <= 4,
          claim.getNumberOfCards());
      Optional<Claim> lastClaim = lastState.getClaim();
      if (lastClaim.isPresent()) {
        // The claim must be lastRank, lastRank+1, or lastRank-1
        check(lastClaim.get().isClose(claim.getCardRank()),
            lastClaim.get().getCardRank(), claim.getCardRank());
      }
      List<Integer> lastWorB =
          turnOfColor.isWhite() ? lastState.getWhite() :  lastState.getBlack();
      List<Integer> diffWorB = subtract(lastWorB, newWorB);
      List<Integer> diffM = subtract(newM, lastM);
      // diffs are equal as sets
      check(ImmutableSet.copyOf(diffWorB).equals(ImmutableSet.copyOf(diffM)), diffWorB, diffM);
      check(diffWorB.size() == claim.getNumberOfCards(), diffWorB.size(), claim.getNumberOfCards());
      // 0) new Set(turn, B/W),
      // 1) new Set(W/B, [...]),
      // 2) new Set(M, [...]),
      // 3) new Set(claim, ...)
      expectedOperations = ImmutableList.<Operation>of(
          new Set(TURN, turnOfColor.getOppositeColor().name()),
          new Set(turnOfColor.name(), newWorB),
          new Set(M, newM),
          new Set(CLAIM, Claim.toClaimEntryInGameState(claim))
          );
    }
    return expectedOperations;
  }

  List<Integer> getIndicesInRange(int fromInclusive, int toInclusive) {
    List<Integer> keys = Lists.newArrayList();
    for (int i = fromInclusive; i <= toInclusive; i++) {
      keys.add(i);
    }
    return keys;
  }

  List<String> getCardsInRange(int fromInclusive, int toInclusive) {
    List<String> keys = Lists.newArrayList();
    for (int i = fromInclusive; i <= toInclusive; i++) {
      keys.add(C + i);
    }
    return keys;
  }

  String cardIdToString(int cardId) {
    checkArgument(cardId >= 0 && cardId < 52);
    int rank = (cardId / 4);
    String rankString = Rank.values()[rank].getFirstLetter();
    int suit = cardId % 4;
    String suitString = Suit.values()[suit].getFirstLetterLowerCase();
    return rankString + suitString;
  }

  <T> List<T> concat(List<T> a, List<T> b) {
    return Lists.newArrayList(Iterables.concat(a, b));
  }

  <T> List<T> subtract(List<T> removeFrom, List<T> elementsToRemove) {
    check(removeFrom.containsAll(elementsToRemove), removeFrom, elementsToRemove);
    List<T> result = Lists.newArrayList(removeFrom);
    result.removeAll(elementsToRemove);
    check(removeFrom.size() == result.size() + elementsToRemove.size());
    return result;
  }

  List<Operation> getInitialMove(int whitePlayerId, int blackPlayerId) {
    List<Operation> operations = Lists.newArrayList();
    // The order of operations: turn, isCheater, W, B, M, claim, C0...C51
    operations.add(new Set(TURN, W));
    // set W and B hands
    operations.add(new Set(W, getIndicesInRange(0, 25)));
    operations.add(new Set(B, getIndicesInRange(26, 51)));
    // middle pile is empty
    operations.add(new Set(M, ImmutableList.of()));
    // sets all 52 cards: set(C0,2h), �, set(C51,Ac)
    for (int i = 0; i < 52; i++) {
      operations.add(new Set(C + i, cardIdToString(i)));
    }
    // shuffle(C0,...,C51)
    operations.add(new Shuffle(getCardsInRange(0, 51)));
    // sets visibility
    for (int i = 0; i < 26; i++) {
      operations.add(new SetVisibility(C + i, ImmutableList.of(whitePlayerId)));
    }
    for (int i = 26; i < 52; i++) {
      operations.add(new SetVisibility(C + i, ImmutableList.of(blackPlayerId)));
    }
    return operations;
  }

  @SuppressWarnings("unchecked")
  private CheatState gameApiStateToCheatState(Map<String, Object> gameApiState) {
    List<Optional<Card>> cards = Lists.newArrayList();
    for (int i = 0; i < 52; i++) {
      String cardString = (String) gameApiState.get(C + i);
      Card card;
      if (cardString == null) {
        card = null;
      } else {
        Rank rank = Rank.fromFirstLetter(cardString.substring(0, 1));
        Suit suit = Suit.fromFirstLetterLowerCase(cardString.substring(1));
        card = new Card(suit, rank);
      }
      cards.add(Optional.fromNullable(card));
    }
    List<Integer> white = (List<Integer>) gameApiState.get(W);
    List<Integer> black = (List<Integer>) gameApiState.get(B);
    List<Integer> middle = (List<Integer>) gameApiState.get(M);
    return new CheatState(
        Color.valueOf((String) gameApiState.get(TURN)),
        ImmutableList.copyOf(cards),
        ImmutableList.copyOf(white), ImmutableList.copyOf(black),
        ImmutableList.copyOf(middle),
        gameApiState.containsKey(IS_CHEATER),
        Optional.fromNullable(
            Claim.fromClaimEntryInGameState((List<String>) gameApiState.get(CLAIM))));
  }

  private void check(boolean val, Object... debugArguments) {
    if (!val) {
      throw new RuntimeException("We have a hacker! debugArguments="
          + Arrays.toString(debugArguments));
    }
  }
}
