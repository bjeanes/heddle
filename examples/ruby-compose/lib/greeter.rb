# Greets people, sometimes with emoji 😀
require "rainbow"

class Greeter
  def initialize(name)
    @name = name
  end

  def greet
    Rainbow("Héllo, #{@name}!").green
  end

  def plain
    Rainbow.uncolor(greet)
  end
end
