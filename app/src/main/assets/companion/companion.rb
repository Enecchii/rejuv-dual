# rejuv-dual companion mod
#
# Runs inside mkxp-z before the game's own scripts. It hooks Graphics.update
# (always available), then lazily installs game-specific hooks once the
# Pokémon Essentials classes exist. It never edits the game's files.
#
# Talks to the Android host through the native `Companion` module:
#   Companion.push(json)  game -> second-screen UI
#   Companion.poll        UI -> game (one JSON string per call, or nil)
#   Companion.active?     true when a UI surface is attached
#
# Everything here is defensive: Rejuvenation is a heavily modified
# Essentials, so every lookup is tried several ways and failures are logged
# to the Diagnostics tab instead of crashing the game.
#
# To iterate without rebuilding the APK, copy this file to
#   <game folder>/rejuv-dual/companion.rb
# and the app will load that copy instead.

unless defined?(Companion)
  # Desktop / test fallback so the mod can be loaded outside the Android build.
  module Companion
    def self.push(_json); end
    def self.poll; nil; end
    def self.active?; false; end
    def self.log(msg); puts "[rejuv-dual] #{msg}"; end
  end
end

module RD
  VERSION = "0.3.0"
  RD_FILE = __FILE__

  STATE_INTERVAL  = 20   # frames between full state pushes outside battle
  BATTLE_INTERVAL = 8    # frames between state pushes in battle
  PROBE_FRAME     = 90   # frame to run the capability probe
  MAX_ERRORS      = 25   # stop reporting after this many errors per site

  COMMAND_LABELS = ["Fight", "Bag", "Pokémon", "Run"].freeze
  STATUS_NAMES = { 0 => nil, 1 => "SLP", 2 => "PSN", 3 => "BRN", 4 => "PAR", 5 => "FRZ" }.freeze

  @frame = 0
  @hooks = {}
  @errors = Hash.new(0)
  @last_state = nil
  @probe_sent = false
  @scene = nil
  @battle = nil
  @menu = nil
  @menu_dirty = false
  @fake_key = nil
  @fake_frames = 0
  @log_lines = []

  class << self
    attr_accessor :scene, :battle, :menu
    attr_reader :frame, :hooks, :log_lines

    # ── Utilities ──────────────────────────────────────────────────────────

    def log(msg)
      line = "[#{@frame}] #{msg}"
      @log_lines << line
      @log_lines.shift while @log_lines.length > 60
      Companion.log(line) rescue nil
    end

    def error(site, e)
      @errors[site] += 1
      return if @errors[site] > MAX_ERRORS
      bt = (e.backtrace || [])[0, 3].join(" | ")
      log("ERROR #{site}: #{e.class}: #{e.message} @ #{bt}")
      log("#{site}: further errors suppressed") if @errors[site] == MAX_ERRORS
    end

    # Runs the block, returns nil on any StandardError.
    def try
      yield
    rescue StandardError
      nil
    end

    # First non-nil result of a list of lambdas.
    def first_of(*fns)
      fns.each do |f|
        v = try { f.call }
        return v unless v.nil?
      end
      nil
    end

    def call_if(obj, *names)
      names.each do |n|
        next unless obj.respond_to?(n)
        v = try { obj.send(n) }
        return v unless v.nil?
      end
      nil
    end

    def ivar(obj, name)
      return nil if obj.nil?
      try { obj.instance_variable_get(name) }
    end

    # ── JSON (ASCII-only output; non-ASCII becomes \uXXXX) ─────────────────

    def json(v)
      case v
      when nil then "null"
      when true then "true"
      when false then "false"
      when Integer then v.to_s
      when Float then (v.nan? || v.infinite?) ? "null" : v.round(4).to_s
      when Symbol then json_str(v.to_s)
      when String then json_str(v)
      when Array then "[" + v.map { |x| json(x) }.join(",") + "]"
      when Hash then "{" + v.map { |k, x| json_str(k.to_s) + ":" + json(x) }.join(",") + "}"
      else json_str(v.to_s)
      end
    end

    def json_str(s)
      s = s.dup
      s.force_encoding("UTF-8")
      s = s.scrub("?") unless s.valid_encoding?
      out = +"\""
      s.each_codepoint do |c|
        case c
        when 0x22 then out << "\\\""
        when 0x5C then out << "\\\\"
        when 0x0A then out << "\\n"
        when 0x0D then out << "\\r"
        when 0x09 then out << "\\t"
        when 0x20..0x7E then out << c.chr
        when 0...0x20 then out << format("\\u%04x", c)
        when 0x10000..0x10FFFF
          c -= 0x10000
          out << format("\\u%04x\\u%04x", 0xD800 + (c >> 10), 0xDC00 + (c & 0x3FF))
        else
          out << format("\\u%04x", c)
        end
      end
      out << "\""
      out
    end

    # Minimal parser for the flat command objects the UI sends:
    #   {"t":"pick","menu":"fight","index":2}
    def parse_command(str)
      h = {}
      str.scan(/"([^"\\]*)"\s*:\s*("(?:[^"\\]|\\.)*"|-?\d+(?:\.\d+)?|true|false|null)/) do |k, raw|
        h[k] =
          case raw
          when "true" then true
          when "false" then false
          when "null" then nil
          when /\A"/ then raw[1..-2].gsub(/\\(.)/, '\1')
          when /\./ then raw.to_f
          else raw.to_i
          end
      end
      h
    end

    def push(hash)
      Companion.push(json(hash))
    end

    # ── Game data helpers ──────────────────────────────────────────────────

    def species_name(sp)
      return nil if sp.nil?
      first_of(
        -> { PBSpecies.getName(sp) if defined?(PBSpecies) && sp.is_a?(Integer) },
        -> { getMonName(sp) if respond_to?(:getMonName, true) },
        -> { $cache.pkmn[sp].name if defined?($cache) && $cache.respond_to?(:pkmn) },
        -> { GameData::Species.get(sp).name if defined?(GameData::Species) },
        -> { sp.is_a?(Symbol) ? sp.to_s.capitalize : nil }
      ) || sp.to_s
    end

    def move_name(id)
      return nil if id.nil? || id == 0
      first_of(
        -> { PBMoves.getName(id) if defined?(PBMoves) && id.is_a?(Integer) },
        -> { getMoveName(id) if respond_to?(:getMoveName, true) },
        -> { $cache.moves[id].name if defined?($cache) && $cache.respond_to?(:moves) },
        -> { GameData::Move.get(id).name if defined?(GameData::Move) },
        -> { id.is_a?(Symbol) ? id.to_s.split("_").map(&:capitalize).join(" ") : nil }
      ) || id.to_s
    end

    def type_name(t)
      return nil if t.nil?
      first_of(
        -> { PBTypes.getName(t) if defined?(PBTypes) && t.is_a?(Integer) },
        -> { $cache.types[t].name if defined?($cache) && $cache.respond_to?(:types) },
        -> { GameData::Type.get(t).name if defined?(GameData::Type) },
        -> { t.is_a?(Symbol) ? t.to_s.capitalize : nil }
      ) || t.to_s
    end

    def status_name(s)
      return nil if s.nil? || s == 0 || s == :NONE || s == false
      return STATUS_NAMES[s] if s.is_a?(Integer)
      s.to_s.upcase[0, 3]
    end

    # Damage multiplier of attack type `atk` against defender types.
    def effectiveness(atk, d1, d2)
      return nil if atk.nil? || d1.nil?
      first_of(
        lambda {
          e1 = PBTypes.getEffectiveness(atk, d1)
          e2 = (d2.nil? || d2 == d1) ? 2 : PBTypes.getEffectiveness(atk, d2)
          (e1 * e2) / 4.0
        },
        -> { PBTypes.getCombinedEffectiveness(atk, d1, d2) / 4.0 }
      )
    end

    def owned?(sp)
      t = $Trainer
      return nil if t.nil? || sp.nil?
      first_of(
        -> { t.owned?(sp) },
        -> { o = t.owned; o.is_a?(Hash) || o.is_a?(Array) ? !!o[sp] : nil },
        -> { t.pokedex.owned?(sp) }
      )
    end

    def mon_types(m)
      t1 = call_if(m, :type1)
      t2 = call_if(m, :type2)
      if t1.nil? && m.respond_to?(:types)
        ts = try { m.types } || []
        t1, t2 = ts[0], ts[1]
      end
      [t1, (t2 == t1 ? nil : t2)]
    end

    def move_info(mv, foe_types = nil)
      return nil if mv.nil?
      id = call_if(mv, :id, :move)
      return nil if id.nil? || id == 0
      type = call_if(mv, :type)
      cat = first_of(
        -> { mv.pbIsPhysical?(type) ? "physical" : (mv.pbIsSpecial?(type) ? "special" : "status") },
        -> { mv.physicalMove? ? "physical" : (mv.specialMove? ? "special" : "status") },
        -> { { 0 => "physical", 1 => "special", 2 => "status" }[mv.category] },
        -> { mv.category.to_s.downcase }
      )
      info = {
        name: call_if(mv, :name) || move_name(id),
        type: type_name(type),
        pp: call_if(mv, :pp),
        maxpp: call_if(mv, :totalpp, :total_pp),
        power: call_if(mv, :basedamage, :basedmg, :power),
        cat: cat
      }
      if foe_types
        info[:eff] = effectiveness(type, foe_types[0], foe_types[1])
      end
      info
    end

    def mon_info(m)
      return nil if m.nil?
      egg = first_of(-> { m.isEgg? }, -> { m.egg? }) || false
      sp = call_if(m, :species)
      types = mon_types(m)
      info = {
        name: egg ? "Egg" : (call_if(m, :name) || species_name(sp)),
        species: egg ? nil : species_name(sp),
        level: call_if(m, :level),
        hp: call_if(m, :hp),
        maxhp: call_if(m, :totalhp, :totalHP, :total_hp),
        status: status_name(call_if(m, :status)),
        egg: egg,
        shiny: first_of(-> { m.isShiny? }, -> { m.shiny? }) || false,
        types: types.compact.map { |t| type_name(t) }
      }
      unless egg
        moves = call_if(m, :moves) || []
        info[:moves] = moves.map { |mv| move_info(mv) }.compact
      end
      info
    end

    def battler_info(b, with_moves = false)
      return nil if b.nil?
      fainted = first_of(-> { b.isFainted? }, -> { b.fainted? }, -> { b.hp <= 0 })
      return nil if fainted.nil? && call_if(b, :hp).nil?
      pk = call_if(b, :pokemon)
      sp = call_if(b, :species)
      types = mon_types(b)
      info = {
        name: call_if(b, :name) || species_name(sp),
        species: species_name(sp),
        level: call_if(b, :level),
        hp: call_if(b, :hp),
        maxhp: call_if(b, :totalhp, :totalHP, :total_hp),
        status: status_name(call_if(b, :status)),
        types: types.compact.map { |t| type_name(t) },
        fainted: !!fainted,
        owned: owned?(sp),
        shiny: pk ? (first_of(-> { pk.isShiny? }, -> { pk.shiny? }) || false) : false
      }
      info[:moves] = (call_if(b, :moves) || []).map { |mv| move_info(mv) }.compact if with_moves
      info
    end

    def battlers
      return [] if @battle.nil?
      call_if(@battle, :battlers) || ivar(@battle, :@battlers) || []
    end

    def wild_battle?
      return nil if @battle.nil?
      first_of(
        -> { @battle.wildBattle? },
        lambda {
          opp = @battle.opponent
          opp.nil? || (opp.respond_to?(:empty?) && opp.empty?)
        }
      )
    end

    def battle_info
      return nil if @battle.nil?
      bs = battlers
      foes = []
      allies = []
      bs.each_with_index do |b, i|
        next if b.nil?
        info = battler_info(b)
        next if info.nil?
        (i.odd? ? foes : allies) << info
      end
      { wild: wild_battle?, foes: foes, allies: allies }
    end

    def trainer_info
      t = $Trainer
      return nil if t.nil?
      {
        name: call_if(t, :name),
        money: call_if(t, :money),
        badges: first_of(-> { t.numbadges }, -> { t.badge_count }, -> { t.badges.count(true) })
      }
    end

    def map_info
      return nil unless defined?($game_map) && $game_map
      id = call_if($game_map, :map_id)
      {
        id: id,
        name: first_of(
          -> { $game_map.name },
          -> { pbGetMapNameFromId(id) },
          -> { load_data("Data/MapInfos.rxdata")[id].name }
        )
      }
    end

    def party_info
      t = $Trainer
      return [] if t.nil?
      (call_if(t, :party) || []).map { |m| mon_info(m) }.compact
    end

    def build_state
      {
        t: "state",
        v: VERSION,
        frame: @frame,
        map: try { map_info },
        trainer: try { trainer_info },
        party: try { party_info } || [],
        battle: try { battle_info }
      }
    end

    # ── Menus (battle command / fight) ─────────────────────────────────────

    def open_menu(kind, battler_index)
      foe_types = nil
      opts =
        case kind
        when "command"
          COMMAND_LABELS.each_with_index.map { |l, i| { label: l, index: i } }
        when "fight"
          b = battlers[battler_index]
          foe = battlers.each_with_index.find { |x, i| i.odd? && x && (try { x.hp.to_i > 0 }) }
          foe_types = foe ? mon_types(foe[0]) : nil
          moves = (call_if(b, :moves) || [])
          moves.each_with_index.map do |mv, i|
            info = move_info(mv, foe_types)
            info ? info.merge(index: i) : nil
          end.compact
        else
          []
        end
      @menu = { kind: kind, battler: battler_index, options: opts }
      push(t: "menu", menu: @menu, active: battler_info(battlers[battler_index]))
    end

    def close_menu(kind)
      return unless @menu && @menu[:kind] == kind
      @menu = nil
      push(t: "menu", menu: nil)
    end

    def scene_window(name)
      sprites = ivar(@scene, :@sprites)
      sprites.is_a?(Hash) ? sprites[name] : nil
    end

    def set_window_index(win, idx)
      return false if win.nil?
      if win.respond_to?(:setIndex) then win.setIndex(idx)
      elsif win.respond_to?(:index=) then win.index = idx
      else win.instance_variable_set(:@index, idx)
      end
      true
    rescue StandardError => e
      error("set_window_index", e)
      false
    end

    def input_const(*names)
      names.each do |n|
        return Input.const_get(n) if Input.const_defined?(n)
      end
      nil
    end

    def fake_key(key_name, frames = 4)
      const =
        case key_name
        when "A", "C", "USE" then input_const(:C, :USE)
        when "B", "BACK" then input_const(:B, :BACK)
        when "X", "ACTION" then input_const(:X, :ACTION)
        when "Y" then input_const(:Y)
        when "Z", "SPECIAL" then input_const(:Z, :SPECIAL)
        when "L" then input_const(:L)
        when "R" then input_const(:R)
        when "UP" then input_const(:UP)
        when "DOWN" then input_const(:DOWN)
        when "LEFT" then input_const(:LEFT)
        when "RIGHT" then input_const(:RIGHT)
        end
      return if const.nil?
      @fake_key = const
      @fake_frames = frames
    end

    # Called from the Input hooks. Consumes the fake key once.
    def fake_trigger?(button)
      return false if @fake_key.nil? || button != @fake_key
      @fake_key = nil
      true
    end

    def fake_press?(button)
      !@fake_key.nil? && button == @fake_key
    end

    def handle_command(cmd)
      case cmd["t"]
      when "pick"
        menu = @menu
        return if menu.nil? || menu[:kind] != cmd["menu"]
        idx = cmd["index"].to_i
        win = scene_window(menu[:kind] == "fight" ? "fightwindow" : "commandwindow")
        if set_window_index(win, idx)
          fake_key("C")
        else
          log("pick: no #{menu[:kind]} window found")
        end
      when "key"
        fake_key(cmd["key"].to_s.upcase)
      when "probe"
        @probe_sent = false
      when "refresh"
        @last_state = nil
      end
    end

    # ── Hook installation ──────────────────────────────────────────────────

    def ours?(meth)
      loc = meth.source_location
      !loc.nil? && loc[0] == RD_FILE
    rescue StandardError
      false
    end

    # (Re)wraps Input.trigger?/repeat?/press? so the UI can inject one key.
    # Re-checked periodically: if a game script redefines one of these after
    # we hooked it, we wrap the new definition too.
    def install_input_hooks
      return unless defined?(Input)
      sc = Input.singleton_class
      wrapped = []
      { trigger?: :fake_trigger?, repeat?: :fake_trigger?, press?: :fake_press? }.each do |meth, check|
        next unless sc.method_defined?(meth)
        next if ours?(Input.method(meth))
        orig = :"rd_orig_#{meth.to_s.delete('?')}"
        sc.send(:alias_method, orig, meth)
        sc.send(:define_method, meth) { |b| RD.send(check, b) || send(orig, b) }
        wrapped << meth
      end
      @hooks[:input] = true
      log("input hooks: #{wrapped.join(', ')}") unless wrapped.empty?
    rescue StandardError => e
      error("install_input_hooks", e)
      @hooks[:input] = :failed
    end

    def install_scene_hooks
      return unless defined?(PokeBattle_Scene)
      klass = PokeBattle_Scene
      installed = []

      if klass.method_defined?(:pbStartBattle) && !klass.method_defined?(:rd_orig_pbStartBattle)
        klass.class_eval do
          alias_method :rd_orig_pbStartBattle, :pbStartBattle
          define_method(:pbStartBattle) do |*args, &blk|
            begin
              RD.scene = self
              RD.battle = args[0] || RD.ivar(self, :@battle)
              RD.battle_started
            rescue StandardError => e
              RD.error("pbStartBattle", e)
            end
            rd_orig_pbStartBattle(*args, &blk)
          end
        end
        installed << :pbStartBattle
      end

      if klass.method_defined?(:pbEndBattle) && !klass.method_defined?(:rd_orig_pbEndBattle)
        klass.class_eval do
          alias_method :rd_orig_pbEndBattle, :pbEndBattle
          define_method(:pbEndBattle) do |*args, &blk|
            begin
              rd_orig_pbEndBattle(*args, &blk)
            ensure
              RD.battle_ended
            end
          end
        end
        installed << :pbEndBattle
      end

      { pbCommandMenu: "command", pbFightMenu: "fight" }.each do |meth, kind|
        orig = :"rd_orig_#{meth}"
        next unless klass.method_defined?(meth) && !klass.method_defined?(orig)
        klass.class_eval do
          alias_method orig, meth
          define_method(meth) do |*args, &blk|
            begin
              RD.scene = self
              RD.battle ||= RD.ivar(self, :@battle)
              RD.open_menu(kind, args[0].is_a?(Integer) ? args[0] : 0)
            rescue StandardError => e
              RD.error(meth.to_s, e)
            end
            begin
              send(orig, *args, &blk)
            ensure
              RD.close_menu(kind)
            end
          end
        end
        installed << meth
      end

      @hooks[:scene] = installed.empty? ? :none : installed
      log("scene hooks: #{installed.join(', ')}")
    rescue StandardError => e
      error("install_scene_hooks", e)
      @hooks[:scene] = :failed
    end

    def battle_started
      @last_state = nil
      info = battle_info
      push(t: "battle", phase: "start", battle: info)
      log("battle start (wild=#{info && info[:wild].inspect})")
    end

    def battle_ended
      @battle = nil
      @scene = nil
      @menu = nil
      @last_state = nil
      push(t: "battle", phase: "end")
      push(t: "menu", menu: nil)
    rescue StandardError => e
      error("battle_ended", e)
    end

    # ── Capability probe (shown on the Diagnostics tab) ────────────────────

    def probe
      consts = %w[PokeBattle_Scene PokeBattle_Battle PokeBattle_Battler PokeBattle_Pokemon
                  PBSpecies PBMoves PBTypes PBItems GameData Game_Map]
      found = consts.map { |c| [c, Object.const_defined?(c)] }.to_h
      scene_methods = %w[pbStartBattle pbEndBattle pbCommandMenu pbFightMenu pbItemMenu pbPartyScreen
                         pbCommandMenuEx pbFightMenuEx pbFrameUpdate]
      sm = if defined?(PokeBattle_Scene)
             scene_methods.map { |m| [m, PokeBattle_Scene.method_defined?(m)] }.to_h
           end
      first = try { $Trainer.party[0] }
      mon_methods = first ? %w[species name level hp totalhp status moves type1 type2 types isEgg? egg? isShiny? shiny?]
                            .select { |m| first.respond_to?(m) } : []
      {
        t: "probe",
        v: VERSION,
        ruby: RUBY_VERSION,
        consts: found,
        scene_methods: sm,
        hooks: @hooks.transform_values { |v| v.is_a?(Array) ? v.map(&:to_s) : v.to_s },
        globals: {
          trainer: !$Trainer.nil?,
          cache: defined?($cache) ? !$cache.nil? : false,
          game_map: defined?($game_map) ? !$game_map.nil? : false
        },
        trainer_class: $Trainer ? $Trainer.class.name : nil,
        mon_class: first ? first.class.name : nil,
        mon_methods: mon_methods,
        species_sample: first ? (try { call_if(first, :species).inspect }) : nil,
        input: { C: input_const(:C, :USE).inspect, B: input_const(:B, :BACK).inspect },
        get_mon_name: respond_to?(:getMonName, true),
        max_tex: (try { Bitmap.max_size }),
        map_optimizer: (try { RD::MapOptimizer.stats.merge(enabled: RD::MapOptimizer.enabled?) }),
        tilemap_kinds: %w[Tilemap CustomTilemap TilemapRenderer TilemapLoader SynchronizedTilemap]
                         .select { |c| Object.const_defined?(c) },
        data_dir: (try { System.data_directory } if defined?(System)),
        data_files: (try { Dir.entries(System.data_directory).reject { |f| f.start_with?(".") }.first(20) } if defined?(System)),
        game_saves: try { Dir.glob("*.rxdata").first(20) },
        log: @log_lines.last(30)
      }
    end

    def write_probe(p)
      Dir.mkdir("rejuv-dual") unless File.directory?("rejuv-dual")
      File.open("rejuv-dual/probe.json", "wb") { |f| f.write(json(p)) }
    rescue StandardError => e
      error("write_probe", e)
    end

    # ── Per-frame tick (called after every Graphics.update) ────────────────

    def tick
      @frame += 1

      polyfill_pathname unless @pathname_done

      if @frame % 30 == 1
        install_scene_hooks if @hooks[:scene].nil? || @hooks[:scene] == :none
        install_input_hooks unless @hooks[:input] == :failed
      end

      # Expire an unconsumed fake key so it can't fire in a later menu.
      if @fake_key
        @fake_frames -= 1
        @fake_key = nil if @fake_frames <= 0
      end

      while (raw = Companion.poll)
        begin
          handle_command(parse_command(raw))
        rescue StandardError => e
          error("handle_command", e)
        end
      end

      if !@probe_sent && @frame >= PROBE_FRAME
        @probe_sent = true
        p = probe
        push(p)
        write_probe(p)
      end

      interval = @battle ? BATTLE_INTERVAL : STATE_INTERVAL
      if @frame % interval == 0 && Companion.active?
        s = json(build_state)
        # Compare without the frame counter so idle screens don't resend.
        key = s.sub(/"frame":\d+,/, "")
        if key != @last_state
          @last_state = key
          Companion.push(s)
        end
      end
    rescue StandardError => e
      error("tick", e)
    end
  end
end

# ── Runtime polyfills ──────────────────────────────────────────────────────
# The Android Ruby build ships Pathname's C extension but not its Ruby half
# (lib/pathname.rb), so methods like Pathname#parent are missing. Rejuvenation
# calls them (e.g. New Game+ save detection on load). Fill in whatever is
# missing, using only String/File so nothing else needs to be loaded.
module RD
  module PathnamePolyfill
    SEP = "/"

    def self.clean(path)
      abs = path.start_with?(SEP)
      out = []
      path.split(SEP).each do |part|
        next if part.empty? || part == "."
        if part == ".."
          if !out.empty? && out.last != ".." then out.pop
          elsif !abs then out << ".."
          end
        else
          out << part
        end
      end
      s = out.join(SEP)
      abs ? SEP + s : (s.empty? ? "." : s)
    end

    METHODS = {
      cleanpath: lambda { |*_| Pathname.new(RD::PathnamePolyfill.clean(to_s)) },
      absolute?: -> { to_s.start_with?("/") },
      relative?: -> { !to_s.start_with?("/") },
      root?: -> { RD::PathnamePolyfill.clean(to_s) == "/" },
      parent: -> { self + ".." },
      :+ => lambda { |other|
        o = other.to_s
        return Pathname.new(RD::PathnamePolyfill.clean(o)) if o.start_with?("/")
        Pathname.new(RD::PathnamePolyfill.clean(File.join(to_s, o)))
      },
      join: lambda { |*args|
        args.reduce(self) { |acc, a| acc + a }
      },
      each_filename: lambda { |&blk|
        parts = to_s.split("/").reject(&:empty?)
        return parts.each unless blk
        parts.each(&blk)
        nil
      },
      children: lambda { |with_directory = true|
        base = to_s
        Dir.entries(base).reject { |e| e == "." || e == ".." }.map do |e|
          with_directory ? Pathname.new(File.join(base, e)) : Pathname.new(e)
        end
      },
      each_child: lambda { |with_directory = true, &blk|
        kids = children(with_directory)
        return kids.each unless blk
        kids.each(&blk)
      },
      descend: lambda { |&blk|
        parts = to_s.split("/").reject(&:empty?)
        acc = to_s.start_with?("/") ? "/" : ""
        list = parts.map { |p| acc = (acc.empty? || acc.end_with?("/")) ? acc + p : acc + "/" + p; Pathname.new(acc) }
        list.unshift(Pathname.new("/")) if to_s.start_with?("/")
        return list.each unless blk
        list.each(&blk)
        nil
      },
      ascend: lambda { |&blk|
        list = descend.to_a.reverse
        return list.each unless blk
        list.each(&blk)
        nil
      },
      mkpath: lambda { |*_|
        acc = ""
        to_s.split("/").each_with_index do |part, i|
          acc = i.zero? ? part : acc + "/" + part
          next if acc.empty?
          Dir.mkdir(acc) unless File.directory?(acc)
        end
        self
      },
      relative_path_from: lambda { |base|
        a = RD::PathnamePolyfill.clean(File.expand_path(to_s)).split("/")
        b = RD::PathnamePolyfill.clean(File.expand_path(base.to_s)).split("/")
        i = 0
        i += 1 while i < a.length && i < b.length && a[i] == b[i]
        rel = ([".."] * (b.length - i)) + a[i..-1]
        Pathname.new(rel.empty? ? "." : rel.join("/"))
      }
    }.freeze

    # Returns the list of methods added (empty once everything exists).
    def self.apply
      return nil unless Object.const_defined?(:Pathname)
      added = []
      METHODS.each do |name, fn|
        next if ::Pathname.method_defined?(name)
        ::Pathname.send(:define_method, name, &fn)
        added << name
      end
      ::Pathname.send(:alias_method, :/, :+) if !::Pathname.method_defined?(:/) && ::Pathname.method_defined?(:+)
      unless Object.private_method_defined?(:Pathname) || Object.method_defined?(:Pathname)
        Object.send(:define_method, :Pathname) { |p| ::Pathname.new(p) }
        Object.send(:private, :Pathname)
      end
      added
    end
  end

  class << self
    def polyfill_pathname
      return if @pathname_done
      added = PathnamePolyfill.apply
      return if added.nil?            # Pathname not loaded yet; try again later
      @pathname_done = true
      log("Pathname polyfill added: #{added.join(', ')}") unless added.empty?
    rescue StandardError => e
      @pathname_done = true
      error("polyfill_pathname", e)
    end
  end
end

begin
  require "pathname"
rescue LoadError, StandardError
  nil
end
RD.polyfill_pathname

# ── Map optimizer ──────────────────────────────────────────────────────────
# Rejuvenation's tilesets are enormous (tens of thousands of pixels tall).
# Mobile GPUs can't hold them as one texture, and splitting them across a
# huge tile atlas breaks rendering on Adreno. Like Kirin/JoiPlay's "Optimize
# maps", build a small per-map tileset holding only the tiles that map uses,
# and hand the Tilemap a remapped copy of the map data. The game's own data
# ($game_map.data, passability, events) is never touched, and nothing is
# written to disk. Create <game>/rejuv-dual/no-map-optimize to turn it off.
module RD
  module MapOptimizer
    THRESHOLD = 2048        # compact tilesets taller than this (px)
    CACHE_MAX = 4           # compacted tilesets kept around
    TILE = 32
    FIRST_TILE_ID = 384     # ids below this are blank/autotiles

    @cache = []             # most recent first: {key:, src:, bitmap:, remap:, count:}
    @in_use = {}            # tilemap object_id => compact bitmap
    @stats = { maps: 0, tiles: 0, last: nil, disabled: nil, errors: 0 }

    class << self
      attr_reader :stats

      def enabled?
        return @enabled unless @enabled.nil?
        @enabled = !File.exist?("rejuv-dual/no-map-optimize")
        @stats[:disabled] = !@enabled
        @enabled
      rescue StandardError
        @enabled = true
      end

      def wants?(tileset)
        enabled? && tileset && !tileset.disposed? && tileset.height > THRESHOLD
      rescue StandardError
        false
      end

      # Table -> [20-byte header, Array of int16]
      def unpack_table(t)
        raw = t._dump(-1)
        [raw[0, 20], raw[20..-1].unpack("s*")]
      end

      def lookup(key)
        i = @cache.index { |e| e[:key] == key && !e[:src].disposed? && !e[:bitmap].disposed? }
        return nil unless i
        e = @cache.delete_at(i)
        @cache.unshift(e)
        e
      end

      def store(entry)
        @cache.unshift(entry)
        while @cache.length > CACHE_MAX
          old = @cache.pop
          in_use = @in_use.values.any? { |b| b.equal?(old[:bitmap]) }
          old[:bitmap].dispose if !in_use && !old[:bitmap].disposed?
        end
        # Drop entries whose source tileset was disposed (map changed).
        @cache.reject! do |e|
          next false unless e[:src].disposed?
          in_use = @in_use.values.any? { |b| b.equal?(e[:bitmap]) }
          e[:bitmap].dispose if !in_use && !e[:bitmap].disposed?
          true
        end
      end

      # Returns [compact_bitmap, remap] for the tiles used in `data`.
      def compact_tileset(tileset, data)
        used = {}
        data.each { |id| used[id] = true if id >= FIRST_TILE_ID }
        ids = used.keys.sort
        key = [tileset.object_id, ids.hash]
        if (e = lookup(key))
          return e
        end

        cols = 8
        rows = [(ids.length + cols - 1) / cols, 1].max
        bmp = Bitmap.new(cols * TILE, rows * TILE)
        remap = {}
        src_rows = tileset.height / TILE
        rect = Rect.new(0, 0, TILE, TILE)
        ids.each_with_index do |id, i|
          idx = id - FIRST_TILE_ID
          sx = (idx % cols) * TILE
          sy = (idx / cols) * TILE
          remap[id] = FIRST_TILE_ID + i
          next if idx / cols >= src_rows
          rect.set(sx, sy, TILE, TILE)
          bmp.blt((i % cols) * TILE, (i / cols) * TILE, tileset, rect)
        end
        e = { key: key, src: tileset, bitmap: bmp, remap: remap, count: ids.length }
        store(e)
        @stats[:maps] += 1
        @stats[:tiles] = ids.length
        @stats[:last] = "#{tileset.width}x#{tileset.height} -> #{bmp.width}x#{bmp.height} (#{ids.length} tiles)"
        RD.log("map optimizer: #{@stats[:last]}")
        e
      end

      def remap_table(header, data, remap)
        out = data.map { |id| id >= FIRST_TILE_ID ? (remap[id] || 0) : id }
        Table._load(header + out.pack("s*"))
      end

      def remap_priorities(prio, remap, count)
        return nil if prio.nil?
        t = Table.new(FIRST_TILE_ID + count)
        n = [prio.xsize, FIRST_TILE_ID].min
        n.times { |i| t[i] = prio[i] }
        remap.each { |old, new| t[new] = old < prio.xsize ? prio[old] : 0 }
        t
      end

      def mark_in_use(tilemap, bitmap)
        if bitmap then @in_use[tilemap.object_id] = bitmap
        else @in_use.delete(tilemap.object_id)
        end
      end

      def error(e)
        @stats[:errors] += 1
        RD.error("map_optimizer", e)
      end
    end
  end
end

if defined?(Tilemap) && !Tilemap.method_defined?(:rd_orig_set_tileset)
  class Tilemap
    alias_method :rd_orig_set_tileset, :tileset=
    alias_method :rd_orig_get_tileset, :tileset
    alias_method :rd_orig_set_map_data, :map_data=
    alias_method :rd_orig_get_map_data, :map_data
    alias_method :rd_orig_set_priorities, :priorities=
    alias_method :rd_orig_get_priorities, :priorities
    alias_method :rd_orig_update, :update
    alias_method :rd_orig_dispose, :dispose if method_defined?(:dispose)

    def tileset=(v)
      @rd_tileset = v
      rd_refresh
      v
    end

    def map_data=(v)
      @rd_map_data = v
      rd_refresh
      v
    end

    def priorities=(v)
      @rd_priorities = v
      rd_refresh
      v
    end

    # Getters return what the game set, so its own code (and disposal of
    # the real tileset) behaves exactly as before.
    def tileset
      defined?(@rd_tileset) ? @rd_tileset : rd_orig_get_tileset
    end

    def map_data
      defined?(@rd_map_data) ? @rd_map_data : rd_orig_get_map_data
    end

    def priorities
      defined?(@rd_priorities) ? @rd_priorities : rd_orig_get_priorities
    end

    def update(*args)
      if @rd_optimized
        @rd_check = (@rd_check || 0) + 1
        if @rd_check >= 30
          @rd_check = 0
          # Tiles changed at runtime (puzzles, events): rebuild.
          begin
            dump = @rd_map_data._dump(-1)
            if dump != @rd_map_dump
              @rd_map_dump = nil
              rd_refresh
            end
          rescue StandardError => e
            RD::MapOptimizer.error(e)
          end
        end
      end
      rd_orig_update(*args)
    end

    if method_defined?(:rd_orig_dispose)
      def dispose(*args)
        RD::MapOptimizer.mark_in_use(self, nil)
        rd_orig_dispose(*args)
      end
    end

    private

    def rd_apply(which, obj)
      @rd_applied ||= {}
      return if @rd_applied.key?(which) && @rd_applied[which].equal?(obj)
      @rd_applied[which] = obj
      case which
      when :tileset then rd_orig_set_tileset(obj)
      when :map_data then rd_orig_set_map_data(obj)
      when :priorities then rd_orig_set_priorities(obj)
      end
    end

    def rd_refresh
      ts = @rd_tileset
      md = @rd_map_data
      if RD::MapOptimizer.wants?(ts)
        # Hold back the huge tileset until we know which tiles are used.
        return if md.nil?
        begin
          dump = md._dump(-1)
          if @rd_map_dump != dump || !@rd_entry || !@rd_entry[:src].equal?(ts) || @rd_entry[:bitmap].disposed?
            header = dump[0, 20]
            data = dump[20..-1].unpack("s*")
            @rd_entry = RD::MapOptimizer.compact_tileset(ts, data)
            @rd_remapped = RD::MapOptimizer.remap_table(header, data, @rd_entry[:remap])
            @rd_map_dump = dump
            @rd_prio_src = :none
          end
          unless @rd_prio_src.equal?(@rd_priorities)
            @rd_prio_src = @rd_priorities
            @rd_prio_remapped = RD::MapOptimizer.remap_priorities(@rd_priorities, @rd_entry[:remap], @rd_entry[:count])
          end
          rd_apply(:tileset, @rd_entry[:bitmap])
          rd_apply(:map_data, @rd_remapped)
          rd_apply(:priorities, @rd_prio_remapped) if @rd_prio_remapped
          RD::MapOptimizer.mark_in_use(self, @rd_entry[:bitmap])
          @rd_optimized = true
          return
        rescue StandardError => e
          RD::MapOptimizer.error(e)
          # fall through to the unoptimized path
        end
      end
      @rd_optimized = false
      @rd_entry = nil
      RD::MapOptimizer.mark_in_use(self, nil)
      rd_apply(:tileset, ts) if ts
      rd_apply(:map_data, md) if md
      rd_apply(:priorities, @rd_priorities) if @rd_priorities
    end
  end
end

# Graphics.update exists before any game script runs, so hook it right away.
# Game scripts that alias Graphics.update later still call through to this.
module Graphics
  class << self
    unless method_defined?(:rd_orig_update)
      alias_method :rd_orig_update, :update
      def update(*args)
        r = rd_orig_update(*args)
        RD.tick
        r
      end
    end
  end
end

RD.log("companion #{RD::VERSION} loaded (Ruby #{RUBY_VERSION})")
